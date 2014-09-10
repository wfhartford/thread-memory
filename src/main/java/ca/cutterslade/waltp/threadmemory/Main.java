package ca.cutterslade.waltp.threadmemory;

import java.lang.management.ManagementFactory;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
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

    @Option(defaultValue = "64", minimum = 1, description = "Number of data blocks to allocate per thread")
    int getBlockCount();

    @Option(defaultValue = "4", minimum = 1, description = "Number of rounds to run through")
    int getRoundCount();

    @Option(defaultValue = "400", minimum = 0, description = "Time in milliseconds to wait between alloctions")
    int getThreadDelay();

    @Option(defaultValue = "200", minimum = 0, description = "Time in milliseconds to wait between reporting outputs")
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
    final Runnable runnable = getRunnable();
    final Thread thread = new Thread(runnable);
    thread.start();
  }

  private Runnable getRunnable() {
    final Runnable runnable;
    switch (parameters.getMode()) {
      case GROW:
        runnable = new GrowingThread();
        break;
      default:
        throw new UnsupportedOperationException("Mode " + parameters.getMode() + " is not yet supported");
    }
    return runnable;
  }

  private abstract class AllocationThread implements Runnable {
    private long threadId;
    private long allocated;
    private long released;

    @Override
    public void run() {
      try {
        synchronized (mutex) {
          threadId = Thread.currentThread().getId();
          doAllocation();
          log.info("Thread {} complete", threadId);
          finishedThreadCount--;
        }
      }
      catch (final InterruptedException e) {
        log.warn("Thread {} interrupted", threadId, e);
      }
    }

    protected abstract void doAllocation() throws InterruptedException;

    protected byte[] getBlock() {
      allocated += parameters.getBlockSize();
      logStats();
      return new byte[parameters.getBlockSize()];
    }

    protected void release(final byte[] block) {
      released += block.length;
      logStats();
    }

    private void logStats() {
      log.debug("Thread {} has allocated {} bytes, released {} bytes = holding {} bytes",
          threadId, allocated, released, allocated - released);
    }
  }

  private class GrowingThread extends AllocationThread {
    @Override
    protected void doAllocation() throws InterruptedException {
      for (int round = 0; round < parameters.getRoundCount(); round++) {
        final List<byte[]> blocks = Lists.newArrayList();
        for (int block = 0; block < parameters.getBlockCount(); block++) {
          blocks.add(getBlock());
          mutex.wait(parameters.getThreadDelay());
        }
        for (final byte[] block : blocks) {
          release(block);
        }
      }
    }
  }
}
