package be.nabu.libs.triton.impl;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.Charset;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLSocket;

import be.nabu.libs.triton.TritonLocalConsole;
import be.nabu.libs.triton.api.ConsoleSource;

public class ConsoleSocketSource implements ConsoleSource {

	private Reader reader;
	private Socket socket;
	private Writer writer;
	private boolean closed = false;

	public ConsoleSocketSource(Socket socket, Charset charset) throws IOException {
		this.socket = socket;
		reader = new InputStreamReader(socket.getInputStream(), charset);
		writer = new OutputStreamWriter(socket.getOutputStream(), charset);
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
		return "socket[" + (certificate == null ? "" : TritonLocalConsole.getValidatedAlias(certificate) + "@") + socket.getRemoteSocketAddress() + "]";
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
}
