package be.nabu.libs.triton.api;

import java.io.Reader;
import java.io.Writer;

public interface ConsoleSource extends AutoCloseable {
	public Reader getReader();
	public Writer getWriter();
	public boolean isClosed();
}
