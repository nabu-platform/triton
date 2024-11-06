/*
* Copyright (C) 2021 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.libs.triton.remote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.events.api.EventDispatcher;
import be.nabu.libs.events.api.EventHandler;
import be.nabu.libs.events.impl.EventDispatcherImpl;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.client.nio.NIOHTTPClientImpl;
import be.nabu.libs.http.core.CustomCookieStore;
import be.nabu.libs.http.server.nio.MemoryMessageDataProvider;
import be.nabu.libs.http.server.websockets.WebSocketUtils;
import be.nabu.libs.http.server.websockets.api.WebSocketMessage;
import be.nabu.libs.http.server.websockets.api.WebSocketRequest;
import be.nabu.libs.http.server.websockets.impl.WebSocketRequestParserFactory;
import be.nabu.libs.nio.api.StandardizedMessagePipeline;
import be.nabu.libs.nio.api.events.ConnectionEvent;
import be.nabu.libs.nio.api.events.ConnectionEvent.ConnectionState;
import be.nabu.libs.types.api.ComplexContent;
import be.nabu.libs.types.api.ComplexType;
import be.nabu.libs.types.binding.api.MarshallableBinding;
import be.nabu.libs.types.binding.api.UnmarshallableBinding;
import be.nabu.libs.types.binding.api.Window;
import be.nabu.libs.types.binding.json.JSONBinding;
import be.nabu.libs.types.binding.xml.XMLBinding;
import be.nabu.libs.types.java.BeanInstance;
import be.nabu.libs.types.java.BeanResolver;

public class TritonRemoteClient {
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	private EventDispatcher dispatcher;
	private SSLContext context;
	private NIOHTTPClientImpl httpClient;
	private String identifier;
	private boolean connected;
	private URI poseidon;
	private Charset charset = Charset.forName("UTF-8");
	private Thread heartbeat;
	
	// defaults to 15 seconds
	private long heartbeatInterval = 15 * 1000;
	
	// the poseidon uri should be a fully qualified http endpoint, e.g.
	// https://example.com/triton/{identifier}
	// the identifier variable will be filled in at runtime
	public TritonRemoteClient(String identifier, URI poseidon) {
		this.identifier = identifier;
		this.poseidon = poseidon;
		// for triton events
		dispatcher = new EventDispatcherImpl();
		try {
			context = SSLContext.getDefault();
		}
		catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
	}
	
	private void registerListeners() {
		dispatcher.subscribe(ConnectionEvent.class, new EventHandler<ConnectionEvent, Void>() {
			@Override
			public Void handle(ConnectionEvent event) {
				if (ConnectionState.CLOSED.equals(event.getState())) {
					logger.warn("Triton connection closed, reconnecting...");
					stopHeartbeat();
					connected = false;
					retryConnect();
				}
				else if (ConnectionState.UPGRADED.equals(event.getState())) {
					logger.info("Triton connection set up");
					connected = true;
					startHeartbeat();
				}
				return null;
			}
		});
	}
	
	public void start() {
		getClient();
	}
	
	private String getTritonPath() {
		return poseidon.getPath() == null ? "/" : poseidon.getPath().replace("{identifier}", identifier);
	}

	private NIOHTTPClientImpl getClient() {
		if (httpClient == null) {
			synchronized(this) {
				if (httpClient == null) {
					httpClient = new NIOHTTPClientImpl(
						context,
						// io pool size
						5,
						// process pool size
						5,
						// max connections per server
						5,
						new EventDispatcherImpl(),
						new MemoryMessageDataProvider(),
						new CookieManager(new CustomCookieStore(), CookiePolicy.ACCEPT_ALL), 
						Executors.defaultThreadFactory()
					);
					// unlimited lifetime
					httpClient.getNIOClient().setMaxLifeTime(0l);
					// register websocket upgrader
					WebSocketUtils.allowWebsockets(httpClient, new MemoryMessageDataProvider());
					
					// listen to connection events
					httpClient.getDispatcher().subscribe(ConnectionEvent.class, new EventHandler<ConnectionEvent, Void>() {
						@Override
						public Void handle(ConnectionEvent event) {
							WebSocketRequestParserFactory parserFactory = WebSocketUtils.getParserFactory(event.getPipeline());
							if (parserFactory != null && getTritonPath().equals(parserFactory.getPath())) {
								dispatcher.fire(event, httpClient);
							}
							return null;
						}
					});
				}
			}
		}
		return httpClient;
	}

	public EventDispatcher getDispatcher() {
		return dispatcher;
	}
	
	private void connect() {
		try {
			HTTPResponse upgrade = WebSocketUtils.upgrade(
				getClient(), 
				context, 
				poseidon.getHost(), 
				poseidon.getPort() < 0 ? ("http".equalsIgnoreCase(poseidon.getScheme()) ? 80 : 443) : poseidon.getPort(), 
				getTritonPath(), 
				// no authentication credentials are passed in
				null, 
				new MemoryMessageDataProvider(), 
				getClient().getDispatcher(), 
				new ArrayList<String>(),
				// no preemptive authorization
				null
			);

			if (upgrade.getCode() >= 100 && upgrade.getCode() < 300) {
				logger.info("Connection upgrade request completed");
			}
			else if (upgrade.getCode() == 503 || upgrade.getCode() == 502) {
				logger.warn("Poseidon temporarily unavailable, retrying...");
				retryConnect();
			}
			else {
				logger.warn("Poseidon connection unavailable: " + upgrade.getCode() + ", not retrying connection");
			}
		}
		catch (Exception e) {
			logger.warn("Could not connect to poseidon, retrying...", e);
			retryConnect();
		}
	}
	
	private void send(TritonMessage message) {
		if (connected) {
			List<StandardizedMessagePipeline<WebSocketRequest, WebSocketMessage>> pipelines = WebSocketUtils.getWebsocketPipelines(getClient().getNIOClient(), getTritonPath());
			if (pipelines != null && pipelines.size() > 0) {
				WebSocketMessage webSocketMessage = WebSocketUtils.newMessage(marshal(message, true));
				pipelines.get(0).getResponseQueue().add(webSocketMessage);
			}
			else {
				logger.warn("Could not send triton message because the connection to poseidon could not be found");
			}
		}
		else {
			logger.warn("Could not send triton message because we are not connection to poseidon");
		}
	}
	
	public String send(String type, int version, Object payload) {
		TritonMessage message = new TritonMessage();
		message.setType(type);
		if (payload != null) {
			message.setPayload(new String(marshal(payload, false), charset));
		}
		message.setVersion(version);
		send(message);
		return message.getId();
	}
	
	public String reply(String conversationId, String type, int version, Object payload) {
		TritonMessage message = new TritonMessage();
		message.setType(type);
		if (payload != null) {
			message.setPayload(new String(marshal(payload, false), charset));
		}
		message.setVersion(version);
		message.setConversationId(conversationId);
		send(message);
		return message.getId();
	}
	
	public <T> byte [] marshal(T content, boolean xml) {
		try {
			BeanInstance<T> beanInstance = new BeanInstance<T>(content);
			MarshallableBinding binding = xml 
				? new XMLBinding(beanInstance.getType(), charset)
				: new JSONBinding(beanInstance.getType(), charset);
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			binding.marshal(output, beanInstance);
			return output.toByteArray();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	public <T> T unmarshal(String input, Class<T> clazz, boolean xml) {
		return unmarshal(new ByteArrayInputStream(input.getBytes(charset)), clazz, xml);
	}
	
	@SuppressWarnings("unchecked")
	public <T> T unmarshal(InputStream input, Class<T> clazz, boolean xml) {
		try {
			UnmarshallableBinding binding = xml
				? new XMLBinding((ComplexType) BeanResolver.getInstance().resolve(clazz), charset)
				: new JSONBinding((ComplexType) BeanResolver.getInstance().resolve(clazz), charset);
			ComplexContent unmarshal = binding.unmarshal(input, new Window[0]);
			return ((BeanInstance<T>) unmarshal).getUnwrapped();
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	private void retryConnect() {
		Thread thread = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					Thread.sleep(10000);
				}
				catch (InterruptedException e) {
					// do nothing
				}
				connect();
			}
		});
		thread.setName("triton-reconnector");
		thread.setDaemon(true);
		thread.start();
	}
	
	private void startHeartbeat() {
		stopHeartbeat();
		heartbeat = new Thread(new Runnable() {
			@Override
			public void run() {
				while (!Thread.interrupted()) {
					
					try {
						Thread.sleep(heartbeatInterval);
					}
					catch (InterruptedException e) {
						break;
					}
				}
			}
		});
		heartbeat.setName("triton-heartbeat");
		heartbeat.setDaemon(true);
		heartbeat.start();
	}
	
	private void stopHeartbeat() {
		if (heartbeat != null) {
			heartbeat.interrupt();
			heartbeat = null;
		}
	}
}
