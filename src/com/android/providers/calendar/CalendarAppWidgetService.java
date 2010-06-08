/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.providers.calendar;

import com.google.common.annotations.VisibleForTesting;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources.NotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.os.IBinder;
import android.provider.Calendar.Attendees;
import android.provider.Calendar.Calendars;
import android.provider.Calendar.Instances;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Arrays;
import java.util.Set;
import java.util.TimeZone;


public class CalendarAppWidgetService extends Service implements Runnable {
    private static final String TAG = "CalendarAppWidgetService";
    private static final boolean LOGD = false;

    private static final String EVENT_SORT_ORDER = "startDay ASC, startMinute ASC, endMinute ASC, "
            + "calendar_id ASC" + " LIMIT 10";

    private static final String EVENT_SELECTION = Calendars.SELECTED + "=1 AND "
            + Instances.SELF_ATTENDEE_STATUS + "!=" + Attendees.ATTENDEE_STATUS_DECLINED;

    static final String[] EVENT_PROJECTION = new String[] {
        Instances.ALL_DAY,
        Instances.BEGIN,
        Instances.END,
        Instances.TITLE,
        Instances.EVENT_LOCATION,
        Instances.EVENT_ID,
    };

    static final int INDEX_ALL_DAY = 0;
    static final int INDEX_BEGIN = 1;
    static final int INDEX_END = 2;
    static final int INDEX_TITLE = 3;
    static final int INDEX_EVENT_LOCATION = 4;
    static final int INDEX_EVENT_ID = 5;

    private static final long SEARCH_DURATION = DateUtils.WEEK_IN_MILLIS;

    private static final long UPDATE_NO_EVENTS = DateUtils.HOUR_IN_MILLIS * 6;

    private static final String KEY_DETAIL_VIEW = "DETAIL_VIEW";

    static class CalendarAppWidgetModel {
        String dayOfWeek;
        String dayOfMonth;
        /*
         * TODO Refactor this so this class is used in the case of "no event"
         * So for now, this field is always View.GONE
         */
        int visibNoEvents;

        EventInfo[] eventInfos;

        int visibConflictPortrait; // Visibility value for conflictPortrait textview
        String conflictPortrait;
        int visibConflictLandscape; // Visibility value for conflictLandscape textview
        String conflictLandscape;

        public CalendarAppWidgetModel() {
            eventInfos = new EventInfo[2];
            eventInfos[0] = new EventInfo();
            eventInfos[1] = new EventInfo();

            visibNoEvents = View.GONE;
            visibConflictPortrait = View.GONE;
            visibConflictLandscape = View.GONE;
        }

        class EventInfo {
            int visibWhen; // Visibility value for When textview (View.GONE or View.VISIBLE)
            String when;
            int visibWhere; // Visibility value for Where textview (View.GONE or View.VISIBLE)
            String where;
            int visibTitle; // Visibility value for Title textview (View.GONE or View.VISIBLE)
            String title;

            public EventInfo() {
                visibWhen = View.GONE;
                visibWhere = View.GONE;
                visibTitle = View.GONE;
            }

            @Override
            public String toString() {
                StringBuilder builder = new StringBuilder();
                builder.append("EventInfo [visibTitle=");
                builder.append(visibTitle);
                builder.append(", title=");
                builder.append(title);
                builder.append(", visibWhen=");
                builder.append(visibWhen);
                builder.append(", when=");
                builder.append(when);
                builder.append(", visibWhere=");
                builder.append(visibWhere);
                builder.append(", where=");
                builder.append(where);
                builder.append("]");
                return builder.toString();
            }

            @Override
            public int hashCode() {
                final int prime = 31;
                int result = 1;
                result = prime * result + getOuterType().hashCode();
                result = prime * result + ((title == null) ? 0 : title.hashCode());
                result = prime * result + visibTitle;
                result = prime * result + visibWhen;
                result = prime * result + visibWhere;
                result = prime * result + ((when == null) ? 0 : when.hashCode());
                result = prime * result + ((where == null) ? 0 : where.hashCode());
                return result;
            }

            @Override
            public boolean equals(Object obj) {
                if (this == obj) {
                    return true;
                }
                if (obj == null) {
                    return false;
                }
                if (getClass() != obj.getClass()) {
                    return false;
                }
                EventInfo other = (EventInfo) obj;
                if (title == null) {
                    if (other.title != null) {
                        return false;
                    }
                } else if (!title.equals(other.title)) {
                    return false;
                }
                if (visibTitle != other.visibTitle) {
                    return false;
                }
                if (visibWhen != other.visibWhen) {
                    return false;
                }
                if (visibWhere != other.visibWhere) {
                    return false;
                }
                if (when == null) {
                    if (other.when != null) {
                        return false;
                    }
                } else if (!when.equals(other.when)) {
                    return false;
                }
                if (where == null) {
                    if (other.where != null) {
                        return false;
                    }
                } else if (!where.equals(other.where)) {
                    return false;
                }
                return true;
            }

            private CalendarAppWidgetModel getOuterType() {
                return CalendarAppWidgetModel.this;
            }
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("\nCalendarAppWidgetModel [eventInfos=");
            builder.append(Arrays.toString(eventInfos));
            builder.append(", visibConflictLandscape=");
            builder.append(visibConflictLandscape);
            builder.append(", conflictLandscape=");
            builder.append(conflictLandscape);
            builder.append(", visibConflictPortrait=");
            builder.append(visibConflictPortrait);
            builder.append(", conflictPortrait=");
            builder.append(conflictPortrait);
            builder.append(", visibNoEvents=");
            builder.append(visibNoEvents);
            builder.append(", dayOfMonth=");
            builder.append(dayOfMonth);
            builder.append(", dayOfWeek=");
            builder.append(dayOfWeek);
            builder.append("]");
            return builder.toString();
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result
                    + ((conflictLandscape == null) ? 0 : conflictLandscape.hashCode());
            result = prime * result
                    + ((conflictPortrait == null) ? 0 : conflictPortrait.hashCode());
            result = prime * result + ((dayOfMonth == null) ? 0 : dayOfMonth.hashCode());
            result = prime * result + ((dayOfWeek == null) ? 0 : dayOfWeek.hashCode());
            result = prime * result + Arrays.hashCode(eventInfos);
            result = prime * result + visibConflictLandscape;
            result = prime * result + visibConflictPortrait;
            result = prime * result + visibNoEvents;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            CalendarAppWidgetModel other = (CalendarAppWidgetModel) obj;
            if (conflictLandscape == null) {
                if (other.conflictLandscape != null) {
                    return false;
                }
            } else if (!conflictLandscape.equals(other.conflictLandscape)) {
                return false;
            }
            if (conflictPortrait == null) {
                if (other.conflictPortrait != null) {
                    return false;
                }
            } else if (!conflictPortrait.equals(other.conflictPortrait)) {
                return false;
            }
            if (dayOfMonth == null) {
                if (other.dayOfMonth != null) {
                    return false;
                }
            } else if (!dayOfMonth.equals(other.dayOfMonth)) {
                return false;
            }
            if (dayOfWeek == null) {
                if (other.dayOfWeek != null) {
                    return false;
                }
            } else if (!dayOfWeek.equals(other.dayOfWeek)) {
                return false;
            }
            if (!Arrays.equals(eventInfos, other.eventInfos)) {
                return false;
            }
            if (visibConflictLandscape != other.visibConflictLandscape) {
                return false;
            }
            if (visibConflictPortrait != other.visibConflictPortrait) {
                return false;
            }
            if (visibNoEvents != other.visibNoEvents) {
                return false;
            }
            return true;
        }
    }

    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);

        // Only start processing thread if not already running
        synchronized (AppWidgetShared.sLock) {
            if (!AppWidgetShared.sUpdateRunning) {
                if (LOGD) Log.d(TAG, "no thread running, so starting new one");
                AppWidgetShared.sUpdateRunning = true;
                new Thread(this).start();
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Thread loop to handle
     */
    public void run() {
        while (true) {
            long now = -1;
            int[] appWidgetIds;
            Set<Long> changedEventIds;

            synchronized (AppWidgetShared.sLock) {
                // Bail out if no remaining updates
                if (!AppWidgetShared.sUpdateRequested) {
                    // Clear current shared state, release wakelock, and stop service
                    if (LOGD) Log.d(TAG, "no requested update or expired wakelock, bailing");
                    AppWidgetShared.clearLocked();
                    stopSelf();
                    return;
                }

                // Clear requested flag and collect latest parameters
                AppWidgetShared.sUpdateRequested = false;

                now = AppWidgetShared.sLastRequest;
                appWidgetIds = AppWidgetShared.collectAppWidgetIdsLocked();
                changedEventIds = AppWidgetShared.collectChangedEventIdsLocked();
            }

            // Process this update
            if (LOGD) Log.d(TAG, "processing requested update now=" + now);
            performUpdate(this, appWidgetIds, changedEventIds, now);
        }
    }

    /**
     * Process and push out an update for the given appWidgetIds.
     *
     * @param context Context to use when updating widget.
     * @param appWidgetIds List of appWidgetIds to update, or null for all.
     * @param changedEventIds Specific events known to be changed, otherwise
     *            null. If present, we use to decide if an update is necessary.
     * @param now System clock time to use during this update.
     */
    private void performUpdate(Context context, int[] appWidgetIds,
            Set<Long> changedEventIds, long now) {
        ContentResolver resolver = context.getContentResolver();

        Cursor cursor = null;
        RemoteViews views = null;
        long triggerTime = -1;

        try {
            cursor = getUpcomingInstancesCursor(resolver, SEARCH_DURATION, now);
            if (cursor != null) {
                MarkedEvents events = buildMarkedEvents(cursor, changedEventIds, now);

                boolean shouldUpdate = true;
                if (changedEventIds.size() > 0) {
                    shouldUpdate = events.watchFound;
                }

                if (events.primaryCount == 0) {
                    views = getAppWidgetNoEvents(context);
                } else if (shouldUpdate) {
                    views = getAppWidgetUpdate(context, cursor, events);
                    triggerTime = calculateUpdateTime(cursor, events);
                }
            } else {
                views = getAppWidgetNoEvents(context);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        // Bail out early if no update built
        if (views == null) {
            if (LOGD) Log.d(TAG, "Didn't build update, possibly because changedEventIds=" +
                    changedEventIds.toString());
            return;
        }

        AppWidgetManager gm = AppWidgetManager.getInstance(context);
        if (appWidgetIds != null && appWidgetIds.length > 0) {
            gm.updateAppWidget(appWidgetIds, views);
        } else {
            ComponentName thisWidget = CalendarAppWidgetProvider.getComponentName(context);
            gm.updateAppWidget(thisWidget, views);
        }

        // Schedule an alarm to wake ourselves up for the next update.  We also cancel
        // all existing wake-ups because PendingIntents don't match against extras.

        // If no next-update calculated, or bad trigger time in past, schedule
        // update about six hours from now.
        if (triggerTime == -1 || triggerTime < now) {
            if (LOGD) Log.w(TAG, "Encountered bad trigger time " + formatDebugTime(triggerTime, now));
            triggerTime = now + UPDATE_NO_EVENTS;
        }

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pendingUpdate = CalendarAppWidgetProvider.getUpdateIntent(context);

        am.cancel(pendingUpdate);
        am.set(AlarmManager.RTC, triggerTime, pendingUpdate);

        if (LOGD) Log.d(TAG, "Scheduled next update at " + formatDebugTime(triggerTime, now));
    }

    /**
     * Format given time for debugging output.
     *
     * @param unixTime Target time to report.
     * @param now Current system time from {@link System#currentTimeMillis()}
     *            for calculating time difference.
     */
    static private String formatDebugTime(long unixTime, long now) {
        Time time = new Time();
        time.set(unixTime);

        long delta = unixTime - now;
        if (delta > DateUtils.MINUTE_IN_MILLIS) {
            delta /= DateUtils.MINUTE_IN_MILLIS;
            return String.format("[%d] %s (%+d mins)", unixTime, time.format("%H:%M:%S"), delta);
        } else {
            delta /= DateUtils.SECOND_IN_MILLIS;
            return String.format("[%d] %s (%+d secs)", unixTime, time.format("%H:%M:%S"), delta);
        }
    }

    /**
     * Convert given UTC time into current local time.
     *
     * @param recycle Time object to recycle, otherwise null.
     * @param utcTime Time to convert, in UTC.
     */
    static private long convertUtcToLocal(Time recycle, long utcTime) {
        if (recycle == null) {
            recycle = new Time();
        }
        recycle.timezone = Time.TIMEZONE_UTC;
        recycle.set(utcTime);
        recycle.timezone = TimeZone.getDefault().getID();
        return recycle.normalize(true);
    }

    /**
     * Figure out the next time we should push widget updates, usually the time
     * calculated by {@link #getEventFlip(Cursor, long, long, boolean)}.
     *
     * @param cursor Valid cursor on {@link Instances#CONTENT_URI}
     * @param events {@link MarkedEvents} parsed from the cursor
     */
    private long calculateUpdateTime(Cursor cursor, MarkedEvents events) {
        long result = -1;
        if (events.primaryRow != -1) {
            cursor.moveToPosition(events.primaryRow);
            long start = cursor.getLong(INDEX_BEGIN);
            long end = cursor.getLong(INDEX_END);
            boolean allDay = cursor.getInt(INDEX_ALL_DAY) != 0;

            // Adjust all-day times into local timezone
            if (allDay) {
                final Time recycle = new Time();
                start = convertUtcToLocal(recycle, start);
                end = convertUtcToLocal(recycle, end);
            }

            result = getEventFlip(cursor, start, end, allDay);

            // Update at midnight
            long midnight = getNextMidnightTimeMillis();
            result = Math.min(midnight, result);
        }
        return result;
    }

    private long getNextMidnightTimeMillis() {
        Time time = new Time();
        time.setToNow();
        time.monthDay++;
        time.hour = 0;
        time.minute = 0;
        time.second = 0;
        long midnight = time.normalize(true);
        return midnight;
    }

    /**
     * Calculate flipping point for the given event; when we should hide this
     * event and show the next one. This is 15 minutes into the event or half
     * way into the event whichever is earlier.
     *
     * @param start Event start time in local timezone.
     * @param end Event end time in local timezone.
     */
    static private long getEventFlip(Cursor cursor, long start, long end, boolean allDay) {
        long duration = end - start;
        return start + Math.min(DateUtils.MINUTE_IN_MILLIS * 15, duration / 2);
    }

    /**
     * Set visibility of various widget components if there are events, or if no
     * events were found.
     *
     * @param views Set of {@link RemoteViews} to apply visibility.
     * @param noEvents True if no events found, otherwise false.
     */
    private void setNoEventsVisible(RemoteViews views, boolean noEvents) {
        views.setViewVisibility(R.id.no_events, noEvents ? View.VISIBLE : View.GONE);

        views.setViewVisibility(R.id.when1, View.GONE);
        views.setViewVisibility(R.id.where1, View.GONE);
        views.setViewVisibility(R.id.title1, View.GONE);
        views.setViewVisibility(R.id.when2, View.GONE);
        views.setViewVisibility(R.id.where2, View.GONE);
        views.setViewVisibility(R.id.title2, View.GONE);
        views.setViewVisibility(R.id.conflict_landscape, View.GONE);
        views.setViewVisibility(R.id.conflict_portrait, View.GONE);
    }

    /**
     * Build a set of {@link RemoteViews} that describes how to update any
     * widget for a specific event instance.
     *
     * @param cursor Valid cursor on {@link Instances#CONTENT_URI}
     * @param events {@link MarkedEvents} parsed from the cursor
     */
    private RemoteViews getAppWidgetUpdate(Context context, Cursor cursor, MarkedEvents events) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.agenda_appwidget);
        setNoEventsVisible(views, false);

        long currentTime = System.currentTimeMillis();
        CalendarAppWidgetModel model = getAppWidgetModel(context, cursor, events, currentTime);

        applyModelToView(model, views);

        // Clicking on the widget launches Calendar
        long startTime;
        if (events.primaryAllDay) {
            startTime = currentTime;
        } else {
            startTime = events.primaryTime;
        }

        PendingIntent pendingIntent = getLaunchPendingIntent(context, startTime);
        views.setOnClickPendingIntent(R.id.agenda_appwidget, pendingIntent);

        return views;
    }

    private void applyModelToView(CalendarAppWidgetModel model, RemoteViews views) {
        views.setTextViewText(R.id.day_of_week, model.dayOfWeek);
        views.setTextViewText(R.id.day_of_month, model.dayOfMonth);
        views.setViewVisibility(R.id.no_events, model.visibNoEvents);
        if (model.visibNoEvents == View.GONE) {
            updateTextView(views, R.id.when1, model.eventInfos[0].visibWhen,
                    model.eventInfos[0].when);
            updateTextView(views, R.id.where1, model.eventInfos[0].visibWhere,
                    model.eventInfos[0].where);
            updateTextView(views, R.id.title1, model.eventInfos[0].visibTitle,
                    model.eventInfos[0].title);
            updateTextView(views, R.id.when2, model.eventInfos[1].visibWhen,
                    model.eventInfos[1].when);
            updateTextView(views, R.id.where2, model.eventInfos[1].visibWhere,
                    model.eventInfos[1].where);
            updateTextView(views, R.id.title2, model.eventInfos[1].visibTitle,
                    model.eventInfos[1].title);

            updateTextView(views, R.id.conflict_portrait, model.visibConflictPortrait,
                    model.conflictPortrait);
            updateTextView(views, R.id.conflict_landscape, model.visibConflictLandscape,
                    model.conflictLandscape);
        }
    }

    static void updateTextView(RemoteViews views, int id, int visibility, String string) {
        views.setViewVisibility(id, visibility);
        if (visibility == View.VISIBLE) {
            views.setTextViewText(id, string);
        }
    }

    static CalendarAppWidgetModel getAppWidgetModel(Context context, Cursor cursor,
            MarkedEvents events, long currentTime) {
        CalendarAppWidgetModel model = new CalendarAppWidgetModel();
        Time time = new Time();
        time.set(currentTime);
        time.monthDay++;
        time.hour = 0;
        time.minute = 0;
        time.second = 0;
        long startOfNextDay = time.normalize(true);

        time.set(currentTime);

        // Calendar header
        String dayOfWeek = DateUtils.getDayOfWeekString(time.weekDay + 1, DateUtils.LENGTH_MEDIUM)
                .toUpperCase();

        model.dayOfWeek = dayOfWeek;
        model.dayOfMonth = Integer.toString(time.monthDay);

        // Fill primary event details
        cursor.moveToPosition(events.primaryRow);
        boolean allDay = cursor.getInt(INDEX_ALL_DAY) != 0;
        populateEvent(context, cursor, model, time, 0, true, startOfNextDay);

        // Conflicts
        int conflictCountLandscape = events.primaryCount - 1;
        if (conflictCountLandscape > 0) {
            model.conflictLandscape = getConflictString(context, conflictCountLandscape);
            model.visibConflictLandscape = View.VISIBLE;
        } else {
            model.visibConflictLandscape = View.GONE;
        }

        int conflictCountPortrait = 0;
        if (events.primaryCount > 2) {
            conflictCountPortrait = events.primaryCount - 1;
        } else if (events.primaryCount == 1 && events.secondaryCount > 1
                && cursor.moveToPosition(events.secondaryRow)) {
            // Show conflict string for the secondary time slot if there is only one event
            // in the primary time slot.
            populateEvent(context, cursor, model, time, 1, false, startOfNextDay);
            conflictCountPortrait = events.secondaryCount;
        }

        if (conflictCountPortrait != 0) {
            model.conflictPortrait = getConflictString(context, conflictCountPortrait);
            model.visibConflictPortrait = View.VISIBLE;
        } else {
            model.visibConflictPortrait = View.GONE;

            // Fill secondary event details
            int secondaryRow = -1;
            if (events.primaryCount == 2) {
                secondaryRow = events.primaryConflictRow;
            } else if (events.primaryCount == 1) {
                secondaryRow = events.secondaryRow;
            }

            if (secondaryRow != -1 && cursor.moveToPosition(secondaryRow)) {
                populateEvent(context, cursor, model, time, 1, true, startOfNextDay);
            }
        }
        return model;
    }

    static private String getConflictString(Context context, int conflictCount) {
        String conflictString;
        try {
            conflictString = context.getResources().getQuantityString(R.plurals.gadget_more_events,
                    conflictCount, conflictCount);
        } catch (NotFoundException e) {
            // Mainly for testing
            if (conflictCount == 1) {
                conflictString = "1 more event";
            } else {
                conflictString = String.format("%d more events", conflictCount);
            }
        }
        return conflictString;
    }

    static private void populateEvent(Context context, Cursor cursor, CalendarAppWidgetModel model,
            Time recycle, int eventIndex, boolean showTitleLocation, long startOfNextDay) {

        // When
        boolean allDay = cursor.getInt(INDEX_ALL_DAY) != 0;
        long start = cursor.getLong(INDEX_BEGIN);
        if (allDay) {
            start = convertUtcToLocal(recycle, start);
        }

        boolean eventIsToday = start < startOfNextDay;
        boolean eventIsTomorrow = !eventIsToday
                && (start < (startOfNextDay + DateUtils.DAY_IN_MILLIS));

        String whenString = "";

        if (!(allDay && eventIsTomorrow)) {
            int flags = DateUtils.FORMAT_ABBREV_ALL;

            if (allDay) {
                flags |= DateUtils.FORMAT_UTC;
                if (!eventIsTomorrow) {
                    flags |= DateUtils.FORMAT_SHOW_WEEKDAY;
                }
            } else {
                flags |= DateUtils.FORMAT_SHOW_TIME;

                if (DateFormat.is24HourFormat(context)) {
                    flags |= DateUtils.FORMAT_24HOUR;
                }

                // Show day or week if different from today
                if (!eventIsTomorrow && !eventIsToday) {
                    flags |= DateUtils.FORMAT_SHOW_WEEKDAY;
                }
            }
            whenString = DateUtils.formatDateRange(context, start, start, flags);
        }

        if (eventIsTomorrow) {
            if (!allDay) {
                whenString += (", ");
            }
            whenString += context.getString(R.string.tomorrow);
        }
        model.eventInfos[eventIndex].when = whenString;
        model.eventInfos[eventIndex].visibWhen = View.VISIBLE;

        if (showTitleLocation) {
            // What
            String titleString = cursor.getString(INDEX_TITLE);
            if (TextUtils.isEmpty(titleString)) {
                titleString = context.getString(R.string.no_title_label);
            }
            model.eventInfos[eventIndex].title = titleString;
            model.eventInfos[eventIndex].visibTitle = View.VISIBLE;

            // Where
            String whereString = cursor.getString(INDEX_EVENT_LOCATION);
            if (!TextUtils.isEmpty(whereString)) {
                model.eventInfos[eventIndex].visibWhere = View.VISIBLE;
                model.eventInfos[eventIndex].where = whereString;
            } else {
                model.eventInfos[eventIndex].visibWhere = View.GONE;
            }
            if (LOGD) Log.d(TAG, " Title:" + titleString + " Where:" + whereString);
        }
    }

    /**
     * Build a set of {@link RemoteViews} that describes an error state.
     */
    private RemoteViews getAppWidgetNoEvents(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.agenda_appwidget);
        setNoEventsVisible(views, true);

        // Calendar header
        Time time = new Time();
        time.setToNow();
        String dayOfWeek = DateUtils.getDayOfWeekString(time.weekDay + 1, DateUtils.LENGTH_MEDIUM)
                .toUpperCase();
        views.setTextViewText(R.id.day_of_week, dayOfWeek);
        views.setTextViewText(R.id.day_of_month, Integer.toString(time.monthDay));

        // Clicking on widget launches the agenda view in Calendar
        PendingIntent pendingIntent = getLaunchPendingIntent(context, 0);
        views.setOnClickPendingIntent(R.id.agenda_appwidget, pendingIntent);

        return views;
    }

    /**
     * Build a {@link PendingIntent} to launch the Calendar app. This correctly
     * sets action, category, and flags so that we don't duplicate tasks when
     * Calendar was also launched from a normal desktop icon.
     * @param goToTime time that calendar should take the user to
     */
    private PendingIntent getLaunchPendingIntent(Context context, long goToTime) {
        Intent launchIntent = new Intent();
        String dataString = "content://com.android.calendar/time";
        launchIntent.setAction(Intent.ACTION_VIEW);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED |
                Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (goToTime != 0) {
            launchIntent.putExtra(KEY_DETAIL_VIEW, true);
            dataString += "/" + goToTime;
        }
        Uri data = Uri.parse(dataString);
        launchIntent.setData(data);
        return PendingIntent.getActivity(context, 0 /* no requestCode */,
                launchIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static class MarkedEvents {
        long primaryTime = -1;
        int primaryRow = -1;
        int primaryConflictRow = -1;
        int primaryCount = 0; // Number of events with same start time as the primary evt.
        boolean primaryAllDay = false;
        long secondaryTime = -1;
        int secondaryRow = -1;
        int secondaryCount = 0; // Number of events with same start time as the secondary evt.
        boolean watchFound = false;
    }

    /**
     * Walk the given instances cursor and build a list of marked events to be
     * used when updating the widget. This structure is also used to check if
     * updates are needed.
     *
     * @param cursor Valid cursor across {@link Instances#CONTENT_URI}.
     * @param watchEventIds Specific events to watch for, setting
     *            {@link MarkedEvents#watchFound} if found during marking.
     * @param now Current system time to use for this update, possibly from
     *            {@link System#currentTimeMillis()}
     */
    @VisibleForTesting
    static MarkedEvents buildMarkedEvents(Cursor cursor, Set<Long> watchEventIds, long now) {
        MarkedEvents events = new MarkedEvents();
        final Time recycle = new Time();

        cursor.moveToPosition(-1);
        while (cursor.moveToNext()) {
            int row = cursor.getPosition();
            long eventId = cursor.getLong(INDEX_EVENT_ID);
            long start = cursor.getLong(INDEX_BEGIN);
            long end = cursor.getLong(INDEX_END);
            boolean allDay = cursor.getInt(INDEX_ALL_DAY) != 0;

            if (LOGD) {
                Log.d(TAG, "Row #" + row + " allDay:" + allDay + " start:" + start + " end:" + end
                        + " eventId:" + eventId);
            }

            // Adjust all-day times into local timezone
            if (allDay) {
                start = convertUtcToLocal(recycle, start);
                end = convertUtcToLocal(recycle, end);
            }

            // Skip events that have already passed their flip times
            long eventFlip = getEventFlip(cursor, start, end, allDay);
            if (LOGD) Log.d(TAG, "Calculated flip time " + formatDebugTime(eventFlip, now));
            if (eventFlip < now) {
                continue;
            }

            // Mark if we've encountered the watched event
            if (watchEventIds != null && watchEventIds.contains(eventId)) {
                events.watchFound = true;
            }

            if (events.primaryRow == -1) {
                // Found first event
                events.primaryRow = row;
                events.primaryTime = start;
                events.primaryAllDay = allDay;
                events.primaryCount = 1;
            } else if (events.primaryTime == start) {
                // Found conflicting primary event
                if (events.primaryConflictRow == -1) {
                    events.primaryConflictRow = row;
                }
                events.primaryCount += 1;
            } else if (events.secondaryRow == -1) {
                // Found second event
                events.secondaryRow = row;
                events.secondaryTime = start;
                events.secondaryCount = 1;
            } else if (events.secondaryTime == start) {
                // Found conflicting secondary event
                events.secondaryCount += 1;
            } else {
                // Nothing interesting about this event, so bail out
                break;
            }
        }
        return events;
    }

    /**
     * Query across all calendars for upcoming event instances from now until
     * some time in the future.
     *
     * @param resolver {@link ContentResolver} to use when querying
     *            {@link Instances#CONTENT_URI}.
     * @param searchDuration Distance into the future to look for event
     *            instances, in milliseconds.
     * @param now Current system time to use for this update, possibly from
     *            {@link System#currentTimeMillis()}.
     */
    private Cursor getUpcomingInstancesCursor(ContentResolver resolver,
            long searchDuration, long now) {
        // Search for events from now until some time in the future
        long end = now + searchDuration;

        Uri uri = Uri.withAppendedPath(Instances.CONTENT_URI,
                String.format("%d/%d", now, end));

        return resolver.query(uri, EVENT_PROJECTION, EVENT_SELECTION, null,
                EVENT_SORT_ORDER);
    }
}
