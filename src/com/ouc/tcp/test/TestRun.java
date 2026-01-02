package com.ouc.tcp.test;

import com.ouc.tcp.app.SystemStart;
import java.io.*;

public class TestRun {
	
	public static void main(String[] args) throws InterruptedException {
		System.out.println("--- TestRun Started (Version 2) ---");
		try {
			// Create a dual output stream to write to both console and file
			File logFile = new File("console_log.txt");
			final FileOutputStream fileOutputStream = new FileOutputStream(logFile);
			final PrintStream originalOut = System.out;
			
			OutputStream teeStream = new OutputStream() {
				@Override
				public void write(int b) throws IOException {
					originalOut.write(b);
					fileOutputStream.write(b);
				}
				
				@Override
				public void write(byte[] b, int off, int len) throws IOException {
					originalOut.write(b, off, len);
					fileOutputStream.write(b, off, len);
				}
				
				@Override
				public void flush() throws IOException {
					originalOut.flush();
					fileOutputStream.flush();
				}
				
				@Override
				public void close() throws IOException {
					originalOut.close();
					fileOutputStream.close();
				}
			};
			
			PrintStream dualStream = new PrintStream(teeStream, true, "UTF-8");
			System.setOut(dualStream);
			
			System.out.println("Logging console output to " + logFile.getAbsolutePath());
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		SystemStart.main(null);
	}
	
}
