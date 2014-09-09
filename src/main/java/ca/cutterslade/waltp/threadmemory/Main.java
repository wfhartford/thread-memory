package ca.cutterslade.waltp.threadmemory;

import java.lang.management.ManagementFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lexicalscope.jewel.cli.Cli;
import com.lexicalscope.jewel.cli.CliFactory;
import com.lexicalscope.jewel.cli.Option;
import com.sun.management.ThreadMXBean;

public final class Main implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(Main.class);

  public enum Mode {
    GROW,
    ROTATING,
    GROW_SHRINK
  }

  public interface Parameters {
    @Option(defaultValue = "10", minimum = 1, description = "Number of threads allocating data")
    int getThreadCount();

    @Option(defaultValue = "2048", minimum = 0, description = "Size of data block allocated by data threads")
    int getBlockSize();

    @Option(defaultValue = "1024", minimum = 1, description = "Number of data blocks to allocate per thread")
    int getBlockCount();

    @Option(defaultValue = "1024", minimum = 1, description = "Number of rounds to run through")
    int getRoundCount();

    @Option(defaultValue = "2000", minimum = 0, description = "Time in milliseconds to wait between alloctions")
    int getThreadDelay();

    @Option(defaultValue = "1000", minimum = 0, description = "Time in milliseconds to wait between reporting outputs")
    long getReportDelay();

    @Option(defaultValue = "GROW", description = "The mode in which data allocating threads should operate")
    Mode getMode();
  }

  public static void main(final String[] args) {
    final Cli<Parameters> cli = CliFactory.createCli(Parameters.class);
    final Parameters parameters = cli.parseArguments(args);
    final Main main = new Main(parameters);
    main.run();
  }

  private final Object mutex = new Object();
  private final ThreadMXBean threadBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();

  private final Parameters parameters;

  private int finishedThreadCount;

  public Main(final Parameters parameters) {
    this.parameters = parameters;
  }

  @Override
  public void run() {
    try {
      synchronized (mutex) {
        startThreads();
        do {
          mutex.wait(parameters.getReportDelay());
          report();
        } while (finishedThreadCount < parameters.getThreadCount());
      }
    }
    catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void report() {
    final long[] allThreadIds = threadBean.getAllThreadIds();
    final long[] allocation = threadBean.getThreadAllocatedBytes(allThreadIds);
    for (int i = 0; i < allThreadIds.length; i++) {
      log.info("Thread {} allocated={}", allThreadIds[i], allocation[i]);
    }
  }

  private void startThreads() {
    for (int i = 0; i < parameters.getThreadCount(); i++) {
      startThread();
    }
  }

  private void startThread() {
  }
}
