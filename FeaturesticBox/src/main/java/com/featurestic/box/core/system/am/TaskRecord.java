package com.featurestic.box.core.system.am;

import android.content.Intent;

import java.util.LinkedList;
import java.util.List;

public class TaskRecord {
    public int id;
    public int userId;
    public String taskAffinity;
    public Intent rootIntent;
    public final List<ActivityRecord> activities = new LinkedList<>();

    public TaskRecord(int id, int userId, String taskAffinity) {
        this.id = id;
        this.userId = userId;
        this.taskAffinity = taskAffinity;
    }

    private static boolean isActivityAlive(ActivityRecord record) {
        if (record == null || record.finished) {
            return false;
        }
        if (record.processRecord != null) {
            if (record.processRecord.bActivityThread == null ||
                    record.processRecord.bActivityThread.asBinder() == null ||
                    !record.processRecord.bActivityThread.asBinder().isBinderAlive()) {
                record.finished = true;
                return false;
            }
        }
        return true;
    }

    public boolean needNewTask() {
        for (ActivityRecord activity : activities) {
            if (isActivityAlive(activity)) {
                return false;
            }
        }
        return true;
    }

    public void addTopActivity(ActivityRecord record) {
        activities.add(record);
    }

    public void removeActivity(ActivityRecord record) {
        activities.remove(record);
    }

    public ActivityRecord getTopActivityRecord() {
        for (int i = activities.size() - 1; i >= 0; i--) {
            ActivityRecord record = activities.get(i);
            if (isActivityAlive(record)) {
                return record;
            }
        }
        return null;
    }
}
