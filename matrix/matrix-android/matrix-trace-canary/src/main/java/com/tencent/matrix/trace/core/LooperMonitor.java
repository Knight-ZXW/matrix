package com.tencent.matrix.trace.core;

import android.os.Build;
import android.os.Looper;
import android.os.MessageQueue;
import android.support.annotation.CallSuper;
import android.util.Printer;

import com.tencent.matrix.util.MatrixLog;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Objects;

public class LooperMonitor implements MessageQueue.IdleHandler {

    private final HashSet<LooperDispatchListener> listeners = new HashSet<>();
    private static final String TAG = "Matrix.LooperMonitor";
    private LooperPrinter printer;
    private Looper looper;

    public abstract static class LooperDispatchListener {

        boolean isHasDispatchStart = false;

        public boolean isValid() {
            return false;
        }


        public void dispatchStart() {

        }

        @CallSuper
        public void onDispatchStart(String x) {
            this.isHasDispatchStart = true;
            dispatchStart();
        }

        @CallSuper
        public void onDispatchEnd(String x) {
            this.isHasDispatchStart = false;
            dispatchEnd();
        }


        public void dispatchEnd() {
        }
    }

    private static final LooperMonitor mainMonitor = new LooperMonitor();

    static void register(LooperDispatchListener listener) {
        mainMonitor.addListener(listener);
    }

    static void unregister(LooperDispatchListener listener) {
        mainMonitor.removeListener(listener);
    }

    public HashSet<LooperDispatchListener> getListeners() {
        return listeners;
    }

    public void addListener(LooperDispatchListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeListener(LooperDispatchListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public LooperMonitor(Looper looper) {
        Objects.requireNonNull(looper);
        this.looper = looper;
        resetPrinter();
        addIdleHandler(looper);
    }

    private LooperMonitor() {
        this(Looper.getMainLooper());
    }

    @Override
    public boolean queueIdle() {
        resetPrinter();
        return true;
    }

    public synchronized void onRelease() {
        if (printer != null) {
            synchronized (listeners) {
                listeners.clear();
            }
            MatrixLog.v(TAG, "[onRelease] %s, origin printer:%s", looper.getThread().getName(), printer.origin);
            looper.setMessageLogging(printer.origin);
            removeIdleHandler(looper);
            looper = null;
            printer = null;
        }
    }

    private synchronized void resetPrinter() {
        final Printer originPrinter = reflectObject(looper, "mLogging");
        if (originPrinter == printer && null != printer) {
            return;
        }
        if (null != printer) {
            MatrixLog.w(TAG, "maybe thread:%s printer[%s] was replace other[%s]!",
                    looper.getThread().getName(), printer, originPrinter);
        }
        looper.setMessageLogging(printer = new LooperPrinter(originPrinter));
        if (null != originPrinter) {
            MatrixLog.i(TAG, "reset printer, originPrinter[%s] in %s", originPrinter, looper.getThread().getName());
        }
    }

    private synchronized void removeIdleHandler(Looper looper) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            looper.getQueue().removeIdleHandler(this);
        } else {
            MessageQueue queue = reflectObject(looper, "mQueue");
            queue.removeIdleHandler(this);
        }
    }

    private synchronized void addIdleHandler(Looper looper) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            looper.getQueue().addIdleHandler(this);
        } else {
            MessageQueue queue = reflectObject(looper, "mQueue");
            queue.addIdleHandler(this);
        }
    }


    class LooperPrinter implements Printer {
        public Printer origin;
        boolean isHasChecked = false;
        boolean isValid = false;

        public LooperPrinter(Printer printer) {
            this.origin = printer;
        }

        @Override
        public void println(String x) {
            if (null != origin) {
                origin.println(x);
                if (origin == this) {
                    throw new RuntimeException(TAG + " origin == this");
                }
            }

            if (!isHasChecked) {
                isValid = x.charAt(0) == '>' || x.charAt(0) == '<';
                isHasChecked = true;
                if (!isValid) {
                    MatrixLog.e(TAG, "[println] Printer is inValid! x:%s", x);
                }
            }

            if (isValid) {
                dispatch(x.charAt(0) == '>', x);
            }

        }
    }


    private void dispatch(boolean isBegin, String log) {

        for (LooperDispatchListener listener : listeners) {
            if (listener.isValid()) {
                if (isBegin) {
                    if (!listener.isHasDispatchStart) {
                        listener.onDispatchStart(log);
                    }
                } else {
                    if (listener.isHasDispatchStart) {
                        listener.onDispatchEnd(log);
                    }
                }
            } else if (!isBegin && listener.isHasDispatchStart) {
                listener.dispatchEnd();
            }
        }

    }

    private static <T> T reflectObject(Object instance, String name) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(instance);
        } catch (Exception e) {
            e.printStackTrace();
            MatrixLog.e(TAG, e.toString());
        }
        return null;
    }

}
