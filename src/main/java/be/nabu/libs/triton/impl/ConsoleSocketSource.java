package be.nabu.libs.triton.impl;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.Charset;

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
		return "socket[" + socket.getRemoteSocketAddress() + "]";
	}

}
