package com.featurestic.box.fake.service;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import black.android.app.job.BRIJobSchedulerStub;
import black.android.app.job.BRJobInfo;
import black.android.content.pm.BRParceledListSlice;
import black.android.os.BRServiceManager;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.entity.AppConfig;
import com.featurestic.box.entity.JobRecord;
import com.featurestic.box.fake.hook.BinderInvocationStub;
import com.featurestic.box.fake.hook.MethodHook;
import com.featurestic.box.fake.hook.ProxyMethod;
import com.featurestic.box.utils.Slog;
import com.featurestic.box.utils.UIDSpoofingHelper;

public class IJobServiceProxy extends BinderInvocationStub {
    private static final String TAG = "JobServiceStub";
    private static final String SERVICE_NAME = "jobscheduler";
    private static final int RESULT_FAILURE = 0;

    public IJobServiceProxy() {
        super(BRServiceManager.get().getService(Context.JOB_SCHEDULER_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder jobScheduler = BRServiceManager.get().getService(SERVICE_NAME);
        return BRIJobSchedulerStub.get().asInterface(jobScheduler);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    private static int findJobInfoIndex(Object[] args) {
        if (args == null) return -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof JobInfo) {
                JobInfo info = (JobInfo) args[i];
                if (info.getService() != null) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findIntIndex(Object[] args) {
        if (args == null) return -1;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer) {
                return i;
            }
        }
        return -1;
    }

    private static JobInfo restoreVirtualJobInfo(JobInfo info) {
        if (info == null) return null;
        try {
            AppConfig config = BActivityThread.getAppConfig();
            if (config != null && config.processName != null) {
                JobRecord record = FeaturesticBoxCore.getBJobManager().queryJobRecord(config.processName, info.getId());
                if (record != null && record.mServiceInfo != null) {
                    BRJobInfo.get(info)._set_service(new ComponentName(record.mServiceInfo.packageName, record.mServiceInfo.name));
                }
            }
        } catch (Throwable ignored) {
        }
        return info;
    }

    @SuppressWarnings("unchecked")
    private static void restoreJobContainer(Object container) {
        if (container == null) return;
        try {
            if (container instanceof JobInfo) {
                restoreVirtualJobInfo((JobInfo) container);
            } else if (container instanceof List) {
                for (Object item : (List<?>) container) {
                    if (item instanceof JobInfo) {
                        restoreVirtualJobInfo((JobInfo) item);
                    }
                }
            } else if (container instanceof Map) {
                for (Object val : ((Map<?, ?>) container).values()) {
                    restoreJobContainer(val);
                }
            } else if (BRParceledListSlice.getRealClass() != null && BRParceledListSlice.getRealClass().isInstance(container)) {
                List<?> list = BRParceledListSlice.get(container).getList();
                if (list != null) {
                    for (Object item : list) {
                        if (item instanceof JobInfo) {
                            restoreVirtualJobInfo((JobInfo) item);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static abstract class BaseJobHandler extends MethodHook {
        protected Object processJobOperation(String operation, Object who, Method method, Object[] args) throws Throwable {
            int jobIndex = findJobInfoIndex(args);
            if (jobIndex < 0) {
                return RESULT_FAILURE;
            }
            JobInfo jobInfo = (JobInfo) args[jobIndex];
            try {
                JobInfo proxyJobInfo = FeaturesticBoxCore.getBJobManager().schedule(jobInfo);
                if (proxyJobInfo != null) {
                    args[jobIndex] = proxyJobInfo;
                    return method.invoke(who, args);
                }
            } catch (Throwable e) {
                Slog.w(TAG, operation + ": FeaturesticBox job manager failed, trying fallback", e);
            }
            return handleWithUIDSpoofing(operation, who, method, args, jobInfo);
        }

        protected Object handleWithUIDSpoofing(String operation, Object who, Method method, Object[] args, JobInfo jobInfo) throws Throwable {
            String targetPackage = jobInfo.getService().getPackageName();
            UIDSpoofingHelper.logUIDInfo("job_" + operation.toLowerCase(), targetPackage);
            if (UIDSpoofingHelper.needsUIDSpoofing("job_" + operation.toLowerCase(), targetPackage)) {
                return RESULT_FAILURE;
            }
            return method.invoke(who, args);
        }

        protected boolean isUIDValidationError(Throwable e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage();
            return message != null && (message.contains("cannot schedule job") || message.contains("UID"));
        }
    }

    @ProxyMethod("schedule")
    public static class Schedule extends BaseJobHandler {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (findJobInfoIndex(args) < 0) {
                    return RESULT_FAILURE;
                }
                return processJobOperation("Schedule", who, method, args);
            } catch (Throwable e) {
                if (isUIDValidationError(e)) {
                    return RESULT_FAILURE;
                }
                return executeFallback(who, method, args, "Schedule");
            }
        }
    }

    @ProxyMethod("enqueue")
    public static class Enqueue extends BaseJobHandler {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                if (findJobInfoIndex(args) < 0) {
                    return RESULT_FAILURE;
                }
                return processJobOperation("Enqueue", who, method, args);
            } catch (Throwable e) {
                if (isUIDValidationError(e)) {
                    return RESULT_FAILURE;
                }
                return executeFallback(who, method, args, "Enqueue");
            }
        }
    }

    @ProxyMethod("cancel")
    public static class Cancel extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                int idIndex = findIntIndex(args);
                if (idIndex < 0) {
                    return method.invoke(who, args);
                }
                int jobId = (Integer) args[idIndex];
                AppConfig config = BActivityThread.getAppConfig();
                if (config != null && config.processName != null) {
                    args[idIndex] = FeaturesticBoxCore.getBJobManager().cancel(config.processName, jobId);
                }
                return method.invoke(who, args);
            } catch (Throwable e) {
                return null;
            }
        }
    }

    @ProxyMethod("cancelAll")
    public static class CancelAll extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                AppConfig config = BActivityThread.getAppConfig();
                if (config != null && config.processName != null) {
                    FeaturesticBoxCore.getBJobManager().cancelAll(config.processName);
                }
                return method.invoke(who, args);
            } catch (Throwable e) {
                return null;
            }
        }
    }

    @ProxyMethod("cancelAllInNamespace")
    public static class CancelAllInNamespace extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                AppConfig config = BActivityThread.getAppConfig();
                if (config != null && config.processName != null) {
                    FeaturesticBoxCore.getBJobManager().cancelAll(config.processName);
                }
                return method.invoke(who, args);
            } catch (Throwable e) {
                return null;
            }
        }
    }

    @ProxyMethod("getPendingJob")
    public static class GetPendingJob extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Object res = method.invoke(who, args);
                if (res instanceof JobInfo) {
                    return restoreVirtualJobInfo((JobInfo) res);
                }
                return res;
            } catch (Throwable e) {
                return null;
            }
        }
    }

    @ProxyMethod("getAllPendingJobs")
    public static class GetAllPendingJobs extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Object res = method.invoke(who, args);
                restoreJobContainer(res);
                return res;
            } catch (Throwable e) {
                return null;
            }
        }
    }

    @ProxyMethod("getAllPendingJobsInNamespace")
    public static class GetAllPendingJobsInNamespace extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                Object res = method.invoke(who, args);
                restoreJobContainer(res);
                return res;
            } catch (Throwable e) {
                return null;
            }
        }
    }

    private static Object executeFallback(Object who, Method method, Object[] args, String operation) {
        try {
            return method.invoke(who, args);
        } catch (Throwable fallbackException) {
            return RESULT_FAILURE;
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
