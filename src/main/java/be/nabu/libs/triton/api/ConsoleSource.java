package be.nabu.libs.triton.api;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;

public interface ConsoleSource extends AutoCloseable {
	public Reader getReader();
	public Writer getWriter();
	public InputStream getInputStream();
	public OutputStream getOutputStream();
	public boolean isClosed();
}
