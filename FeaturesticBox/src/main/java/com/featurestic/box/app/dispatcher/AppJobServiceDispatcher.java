package com.featurestic.box.app.dispatcher;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.res.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.app.BActivityThread;
import com.featurestic.box.entity.JobRecord;

public class AppJobServiceDispatcher {
    private static final AppJobServiceDispatcher sServiceDispatcher = new AppJobServiceDispatcher();
    private final Map<Integer, JobRecord> mJobRecords = new ConcurrentHashMap<>();

    public static AppJobServiceDispatcher get() {
        return sServiceDispatcher;
    }

    public boolean onStartJob(JobParameters params) {
        if (params == null) {
            return false;
        }
        try {
            JobService jobService = getJobService(params.getJobId());
            if (jobService == null) {
                return false;
            }
            return jobService.onStartJob(params);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return false;
    }

    public boolean onStopJob(JobParameters params) {
        if (params == null) {
            return false;
        }
        JobService jobService = getJobService(params.getJobId());
        if (jobService == null) {
            return false;
        }
        boolean isStopJob = false;
        try {
            isStopJob = jobService.onStopJob(params);
        } catch (Throwable ignored) {
        }
        try {
            jobService.onDestroy();
        } catch (Throwable ignored) {
        }
        mJobRecords.remove(params.getJobId());
        return isStopJob;
    }

    public void onConfigurationChanged(Configuration newConfig) {
        for (JobRecord jobRecord : mJobRecords.values()) {
            try {
                if (jobRecord != null && jobRecord.mJobService != null) {
                    jobRecord.mJobService.onConfigurationChanged(newConfig);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public void onDestroy() {
        for (JobRecord jobRecord : mJobRecords.values()) {
            try {
                if (jobRecord != null && jobRecord.mJobService != null) {
                    jobRecord.mJobService.onDestroy();
                }
            } catch (Throwable ignored) {
            }
        }
        mJobRecords.clear();
    }

    public void onLowMemory() {
        for (JobRecord jobRecord : mJobRecords.values()) {
            try {
                if (jobRecord != null && jobRecord.mJobService != null) {
                    jobRecord.mJobService.onLowMemory();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public void onTrimMemory(int level) {
        for (JobRecord jobRecord : mJobRecords.values()) {
            try {
                if (jobRecord != null && jobRecord.mJobService != null) {
                    jobRecord.mJobService.onTrimMemory(level);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    synchronized JobService getJobService(int jobId) {
        JobRecord jobRecord = mJobRecords.get(jobId);
        if (jobRecord != null && jobRecord.mJobService != null) {
            return jobRecord.mJobService;
        }

        try {
            String processName = BActivityThread.getAppProcessName();
            if (processName == null) {
                return null;
            }
            JobRecord record = FeaturesticBoxCore.getBJobManager().queryJobRecord(processName, jobId);
            if (record == null || record.mServiceInfo == null) {
                return null;
            }

            record.mJobService = BActivityThread.currentActivityThread().createJobService(record.mServiceInfo);
            if (record.mJobService == null) {
                return null;
            }

            mJobRecords.put(jobId, record);
            return record.mJobService;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }
}
