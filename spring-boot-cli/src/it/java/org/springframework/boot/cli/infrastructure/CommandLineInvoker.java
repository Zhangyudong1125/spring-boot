/*
 * Copyright 2012-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.cli.infrastructure;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.util.Assert;

/**
 * Utility to invoke the command line in the same way as a user would, i.e. via the shell
 * script in the package's bin directory.
 *
 * @author Andy Wilkinson
 * @author Phillip Webb
 */
public final class CommandLineInvoker {

	private final File workingDirectory;

	public CommandLineInvoker() {
		this(new File("."));
	}

	public CommandLineInvoker(File workingDirectory) {
		this.workingDirectory = workingDirectory;
	}

	public Invocation invoke(String... args) throws IOException {
		return new Invocation(runCliProcess(args));
	}

	private Process runCliProcess(String... args) throws IOException {
		List<String> command = new ArrayList<String>();
		command.add(findLaunchScript().getAbsolutePath());
		command.addAll(Arrays.asList(args));
		ProcessBuilder processBuilder = new ProcessBuilder(command)
				.directory(this.workingDirectory);
		processBuilder.environment().remove("JAVA_OPTS");
		return processBuilder.start();
	}

	private File findLaunchScript() {
		File dir = new File("target");
		dir = dir.listFiles(new FileFilter() {
			@Override
			public boolean accept(File pathname) {
				return pathname.isDirectory() && pathname.getName().contains("-bin");
			}
		})[0];
		dir = new File(dir,
				dir.getName().replace("-bin", "").replace("spring-boot-cli", "spring"));
		dir = new File(dir, "bin");
		File launchScript = new File(dir, isWindows() ? "spring.bat" : "spring");
		Assert.state(launchScript.exists() && launchScript.isFile(),
				"Could not find CLI launch script " + launchScript.getAbsolutePath());
		return launchScript;
	}

	private boolean isWindows() {
		return File.separatorChar == '\\';
	}

	/**
	 * An ongoing Process invocation.
	 */
	public static final class Invocation {

		private final StringBuffer err = new StringBuffer();

		private final StringBuffer out = new StringBuffer();

		private final StringBuffer combined = new StringBuffer();

		private final Process process;

		private final List<Thread> streamReaders = new ArrayList<Thread>();

		public Invocation(Process process) {
			this.process = process;
			this.streamReaders.add(new Thread(new StreamReadingRunnable(
					this.process.getErrorStream(), this.err, this.combined)));
			this.streamReaders.add(new Thread(new StreamReadingRunnable(
					this.process.getInputStream(), this.out, this.combined)));
			for (Thread streamReader : this.streamReaders) {
				streamReader.start();
			}
		}

		public String getOutput() {
			return postProcessLines(getLines(this.combined));
		}

		public String getErrorOutput() {
			return postProcessLines(getLines(this.err));
		}

		public String getStandardOutput() {
			return postProcessLines(getStandardOutputLines());
		}

		public List<String> getStandardOutputLines() {
			return getLines(this.out);
		}

		private String postProcessLines(List<String> lines) {
			StringWriter out = new StringWriter();
			PrintWriter printOut = new PrintWriter(out);
			for (String line : lines) {
				if (!line.startsWith("Maven settings decryption failed")) {
					printOut.println(line);
				}
			}
			return out.toString();
		}

		private List<String> getLines(StringBuffer buffer) {
			BufferedReader reader = new BufferedReader(
					new StringReader(buffer.toString()));
			String line;
			List<String> lines = new ArrayList<String>();
			try {
				while ((line = reader.readLine()) != null) {
					lines.add(line);
				}
			}
			catch (IOException ex) {
				throw new RuntimeException("Failed to read output");
			}
			return lines;
		}

		public int await() throws InterruptedException {
			for (Thread streamReader : this.streamReaders) {
				streamReader.join();
			}
			return this.process.waitFor();
		}

		/**
		 * {@link Runnable} to copy stream output.
		 */
		private final class StreamReadingRunnable implements Runnable {

			private final InputStream stream;

			private final StringBuffer[] outputs;

			private final byte[] buffer = new byte[4096];

			private StreamReadingRunnable(InputStream stream, StringBuffer... outputs) {
				this.stream = stream;
				this.outputs = outputs;
			}

			@Override
			public void run() {
				int read;
				try {
					while ((read = this.stream.read(this.buffer)) > 0) {
						for (StringBuffer output : this.outputs) {
							output.append(new String(this.buffer, 0, read));
						}
					}
				}
				catch (IOException ex) {
					// Allow thread to die
				}
			}

		}

	}

}
