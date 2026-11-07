package com.featurestic.box.core.system.am;

import android.app.job.JobInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.RemoteException;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.Map;

import black.android.app.job.BRJobInfo;
import com.featurestic.box.FeaturesticBoxCore;
import com.featurestic.box.core.system.BProcessManagerService;
import com.featurestic.box.core.system.ISystemService;
import com.featurestic.box.core.system.ProcessRecord;
import com.featurestic.box.core.system.pm.BPackageManagerService;
import com.featurestic.box.entity.JobRecord;
import com.featurestic.box.proxy.ProxyManifest;
import com.featurestic.box.utils.FileUtils;

/**
 * Created by @FeaturesticBox on 4/2/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BJobManagerService extends IBJobManagerService.Stub implements ISystemService {
    private static final BJobManagerService sService = new BJobManagerService();

    // process_jobId
    private final Map<String, JobRecord> mJobRecords = new HashMap<>();

    public static BJobManagerService get() {
        return sService;
    }

    @Override
    public JobInfo schedule(JobInfo info, int userId) throws RemoteException {
        ComponentName componentName = info.getService();
        Intent intent = new Intent();
        intent.setComponent(componentName);
        ResolveInfo resolveInfo = BPackageManagerService.get().resolveService(intent, FileUtils.FileMode.MODE_IWUSR, null, userId);
        if (resolveInfo == null) {
            return info;
        }
        ServiceInfo serviceInfo = resolveInfo.serviceInfo;
        ProcessRecord processRecord = BProcessManagerService.get().findProcessRecord(serviceInfo.packageName, serviceInfo.processName, userId);
        if (processRecord == null) {
            processRecord = BProcessManagerService.get().
                    startProcessLocked(serviceInfo.packageName, serviceInfo.processName, userId, -1, Binder.getCallingPid());
            if (processRecord == null) {
                throw new RuntimeException(
                        "Unable to create Process " + serviceInfo.processName);
            }
        }
        return scheduleJob(processRecord, info, serviceInfo);
    }

    @Override
    public JobRecord queryJobRecord(String processName, int jobId, int userId) throws RemoteException {
        synchronized (mJobRecords) {
            return mJobRecords.get(formatKey(processName, jobId));
        }
    }

    public JobInfo scheduleJob(ProcessRecord processRecord, JobInfo info, ServiceInfo serviceInfo) {
        JobRecord jobRecord = new JobRecord();
        jobRecord.mJobInfo = info;
        jobRecord.mServiceInfo = serviceInfo;

        synchronized (mJobRecords) {
            mJobRecords.put(formatKey(processRecord.processName, info.getId()), jobRecord);
        }
        BRJobInfo.get(info)._set_service(new ComponentName(FeaturesticBoxCore.getHostPkg(), ProxyManifest.getProxyJobService(processRecord.bpid)));
        return info;
    }

    @Override
    public void cancelAll(String processName, int userId) throws RemoteException {
        if (TextUtils.isEmpty(processName)) return;
        synchronized (mJobRecords) {
            java.util.Iterator<String> it = mJobRecords.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
                if (key != null && key.startsWith(processName + "_")) {
                    it.remove();
                }
            }
        }
    }

    @Override
    public int cancel(String processName, int jobId, int userId) throws RemoteException {
        if (!TextUtils.isEmpty(processName)) {
            synchronized (mJobRecords) {
                mJobRecords.remove(formatKey(processName, jobId));
            }
        }
        return jobId;
    }

    private String formatKey(String processName, int jobId) {
        return processName + "_" + jobId;
    }

    @Override
    public void systemReady() {

    }
}
