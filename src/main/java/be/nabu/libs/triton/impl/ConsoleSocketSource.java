package be.nabu.libs.triton.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

import javax.net.ssl.SSLSocket;

import be.nabu.libs.triton.Triton;
import be.nabu.libs.triton.TritonLocalConsole;
import be.nabu.libs.triton.api.ConsoleSource;
import be.nabu.utils.io.blocking.DeblockingInputStream;
import be.nabu.utils.io.blocking.LoggingInputStream;
import be.nabu.utils.io.blocking.LoggingOutputStream;

public class ConsoleSocketSource implements ConsoleSource {

	private Reader reader;
	private Socket socket;
	private Writer writer;
	private boolean closed = false;
	private Date lastRead = new Date();
	private Charset charset;
	private DeblockingInputStream deblockingInput;
	private InputStream mainInput;

	public ConsoleSocketSource(Socket socket, Charset charset) throws IOException {
		this.socket = socket;
		this.charset = charset;
		// deblocking magic at the root to support closing stuff for example for processes
		deblockingInput = new DeblockingInputStream(socket.getInputStream());
		mainInput = deblockingInput.newInputStream();
		reader = new InputStreamReader(getInputStream(), charset);
		writer = new OutputStreamWriter(getOutputStream(), charset);
	}
	
	@Override
	public Reader getReader() {
		return reader;
	}

	@Override
	public Writer getWriter() {
		return writer;
	}

	@Override
	public boolean isClosed() {
		return socket.isClosed() || closed;
	}

	@Override
	public void close() throws Exception {
		closed = true;
		socket.close();
	}
	
	@Override
	public String toString() {
		X509Certificate certificate = getCertificate();
		return "socket[" + (certificate == null ? "" : TritonLocalConsole.getAlias(certificate) + "@") + socket.getRemoteSocketAddress() + "]";
	}
	
	public SocketAddress getRemote() {
		return socket.getRemoteSocketAddress();
	}
	
	public Socket getSocket() {
		return socket;
	}

	public X509Certificate getCertificate() {
		try {
			if (socket instanceof SSLSocket) {
				Certificate[] peerCertificates = ((SSLSocket) socket).getSession().getPeerCertificates();
				if (peerCertificates.length > 0 && peerCertificates[0] instanceof X509Certificate) {
					return (X509Certificate) peerCertificates[0];
				}
			}
		}
		catch (Exception e) {
			// ignore
		}
		return null;
	}

	@Override
	public InputStream getInputStream() {
		return Triton.DEBUG ? new LoggingInputStream(mainInput) : mainInput;
	}

	@Override
	public OutputStream getOutputStream() {
		try {
			return Triton.DEBUG ? new LoggingOutputStream(socket.getOutputStream()) : socket.getOutputStream();
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public void setLastRead(Date lastRead) {
		this.lastRead = lastRead;
	}

	@Override
	public Date getLastRead() {
		return lastRead;
	}

	@Override
	public Charset getCharset() {
		return charset;
	}

	public DeblockingInputStream getDeblockingInput() {
		return deblockingInput;
	}

}
