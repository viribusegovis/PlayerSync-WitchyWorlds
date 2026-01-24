package vip.fubuki.playersync.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

public class PSThreadPoolFactory implements ThreadFactory {
   private final AtomicInteger threadIdx = new AtomicInteger(0);
   private final String threadNamePrefix;

   public PSThreadPoolFactory(String Prefix) {
      this.threadNamePrefix = Prefix;
   }

   @Override
   public Thread newThread(@Nonnull Runnable runnable) {
      Thread thread = new Thread(runnable);
      thread.setName(this.threadNamePrefix + "-thread-" + this.threadIdx.getAndIncrement());
      return thread;
   }
}
