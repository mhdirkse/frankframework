package org.frankframework.filesystem;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.frankframework.ftp.FTPFileRef;
import org.frankframework.testutil.junit.LocalFileServer;
import org.frankframework.testutil.junit.LocalFileServer.FileSystemType;
import org.frankframework.testutil.junit.LocalFileSystemMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * @author Niels Meijer
 */
public class FtpFileSystemTest extends FileSystemTest<FTPFileRef, FtpFileSystem> {

	private final String username = "frankframework";
	private final String password = "pass_123";
	private final String host = "localhost";
	private int port = 21;
	private final String remoteDirectory = "/home";

	@LocalFileSystemMock
	private static LocalFileServer fs;

	@Override
	protected IFileSystemTestHelper getFileSystemTestHelper() {
		if("localhost".equals(host)) {
			return new LocalFileSystemTestHelper(fs.getTestDirectory());
		}
		return new FtpFileSystemTestHelper(username, password, host, remoteDirectory, port);
	}

	@Override
	@BeforeEach
	public void setUp() throws Exception {
		if("localhost".equals(host)) {
			fs.startServer(FileSystemType.FTP);
			port = fs.getPort();
		}

		super.setUp();
	}

	// This test doesn't work with the FTP STUB, it assumes that writing to a file removes the old file, which the STUB does not do.
	@Test
	@Override
	public void writableFileSystemTestTruncateFile() throws Exception {
		assumeFalse(host.equals("localhost"));
		super.writableFileSystemTestTruncateFile();
	}

	@Override
	public FtpFileSystem createFileSystem() {
		FtpFileSystem fileSystem = new FtpFileSystem();
		fileSystem.setHost(host);
		fileSystem.setUsername(username);
		fileSystem.setPassword(password);
		fileSystem.setRemoteDirectory(remoteDirectory);
		fileSystem.setPort(port);

		return fileSystem;
	}
}
