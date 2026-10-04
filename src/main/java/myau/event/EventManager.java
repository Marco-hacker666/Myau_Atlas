package myau.event;

import myau.event.events.Event;
import myau.event.events.EventStoppable;
import myau.event.types.EventType;
import myau.event.types.Priority;
import myau.events.PacketEvent;
import myau.module.Module;
import myau.util.ActionLedger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * @author DarkMagician6
 * @since February 2, 2014
 */
public final class EventManager {
    /**
     * All registered MethodData, keyed by the event class of the method's parameter.
     *
     * Read by every dispatch, and dispatch happens on the network thread
     * (every received packet, and the keep-alive reply) as well as the client
     * thread. Registration is startup-only today, so a plain HashMap happened
     * to be safe; a concurrent map keeps it safe if anything ever registers at
     * runtime, and changes nothing else (no null keys or values are used).
     */
    private static final Map<Class<? extends Event>, List<MethodData>> REGISTRY_MAP =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * All methods in this class are static so there would be no reason to create an object of the EventManager class.
     */
    private EventManager() {
    }

    /**
     * Registers all the methods marked with the EventTarget annotation in the class of the given Object.
     *
     * @param object Object that you want to register.
     */
    public static void register(Object object) {
        for (final Method method : object.getClass().getDeclaredMethods()) {
            if (!isMethodBad(method)) {
                register(method, object);
            }
        }
    }

    /**
     * Registers the methods marked with the EventTarget annotation and that require
     * the specified Event as the parameter in the class of the given Object.
     *
     * @param object     Object that contains the Method you want to register.
     * @param eventClass class for the marked method we are looking for.
     */
    public static void register(Object object, Class<? extends Event> eventClass) {
        for (final Method method : object.getClass().getDeclaredMethods()) {
            if (!isMethodBad(method, eventClass)) {
                register(method, object);
            }
        }
    }

    /**
     * Unregisters all the methods inside the Object that are marked with the EventTarget annotation.
     *
     * @param object Object of which you want to unregister all Methods.
     */
    public static void unregister(Object object) {
        for (final List<MethodData> dataList : REGISTRY_MAP.values()) {
            for (final MethodData data : dataList) {
                if (data.getSource().equals(object)) {
                    dataList.remove(data);
                }
            }
        }
        cleanMap(true);
    }

    /**
     * Unregisters all the methods in the given Object that have the specified class as a parameter.
     *
     * @param object     Object that implements the Listener interface.
     * @param eventClass class for the method to remove.
     */
    public static void unregister(Object object, Class<? extends Event> eventClass) {
        if (REGISTRY_MAP.containsKey(eventClass)) {
            for (final MethodData data : REGISTRY_MAP.get(eventClass)) {
                if (data.getSource().equals(object)) {
                    REGISTRY_MAP.get(eventClass).remove(data);
                }
            }
            cleanMap(true);
        }
    }

    /**
     * Registers a new MethodData to the HashMap.
     * If the HashMap already contains the key of the Method's first argument it will add
     * a new MethodData to key's matching list and sorts it based on Priority. @see com.darkmagician6.eventapi.types.Priority
     * Otherwise it will put a new entry in the HashMap with a the first argument's class
     * and a new CopyOnWriteArrayList containing the new MethodData.
     *
     * @param method Method to register to the HashMap.
     * @param object Source object of the method.
     */
    private static void register(Method method, Object object) {
        Class<? extends Event> indexClass = (Class<? extends Event>) method.getParameterTypes()[0];
        //New MethodData from the Method we are registering.
        EventTarget annotation = method.getAnnotation(EventTarget.class);
        final MethodData data = new MethodData(object, method, annotation.value(), annotation.whenDisabled());
        //Set's the method to accessible so that we can also invoke it if it's protected or private.
        if (!data.getTarget().isAccessible()) {
            data.getTarget().setAccessible(true);
        }
        if (REGISTRY_MAP.containsKey(indexClass)) {
            /* MethodData has no equals(), so contains() only ever matched the
               same instance and a second register() of one object added every
               handler again -- called twice per event. Compared by what it
               means instead: the same method on the same object. */
            if (!isRegistered(REGISTRY_MAP.get(indexClass), object, method)) {
                REGISTRY_MAP.get(indexClass).add(data);
                sortListValue(indexClass);
            }
        } else {
            REGISTRY_MAP.put(indexClass, new CopyOnWriteArrayList<MethodData>() {
                //Eclipse was bitching about a serialVersionUID.
                private static final long serialVersionUID = 666L;

                {
                    add(data);
                }
            });
        }
    }

    /**
     * Removes an entry based on the key value in the map.
     *
     * @param indexClass They index key in the map of which the entry should be removed.
     */
    public static void removeEntry(Class<? extends Event> indexClass) {
        Iterator<Map.Entry<Class<? extends Event>, List<MethodData>>> mapIterator = REGISTRY_MAP.entrySet().iterator();
        while (mapIterator.hasNext()) {
            if (mapIterator.next().getKey().equals(indexClass)) {
                mapIterator.remove();
                break;
            }
        }
    }

    /**
     * Cleans up the map entries.
     * Uses an iterator to make sure that the entry is completely removed.
     *
     * @param onlyEmptyEntries If true only remove the entries with an empty list, otherwise remove all the entries.
     */
    public static void cleanMap(boolean onlyEmptyEntries) {
        Iterator<Map.Entry<Class<? extends Event>, List<MethodData>>> mapIterator = REGISTRY_MAP.entrySet().iterator();
        while (mapIterator.hasNext()) {
            /* next() first, always: with onlyEmptyEntries false the original
               short-circuited past it and remove() threw
               IllegalStateException on the first entry. */
            Map.Entry<Class<? extends Event>, List<MethodData>> entry = mapIterator.next();
            if (!onlyEmptyEntries || entry.getValue().isEmpty()) {
                mapIterator.remove();
            }
        }
    }

    private static boolean isRegistered(List<MethodData> list, Object object, Method method) {
        for (MethodData existing : list) {
            if (existing.getSource() == object && existing.getTarget().equals(method)) {
                return true;
            }
        }
        return false;
    }

    /** Test support only: forgets every registration. Package-private on purpose. */
    static void clearForTests() {
        REGISTRY_MAP.clear();
    }

    /**
     * Sorts the List that matches the corresponding Event class based on priority value.
     *
     * @param indexClass The Event class index in the HashMap of the List to sort.
     */
    private static void sortListValue(Class<? extends Event> indexClass) {
        List<MethodData> sortedList = new CopyOnWriteArrayList<>();
        for (final byte priority : Priority.VALUE_ARRAY) {
            for (final MethodData data : REGISTRY_MAP.get(indexClass)) {
                if (data.getPriority() == priority) {
                    sortedList.add(data);
                }
            }
        }
        //Overwriting the existing entry.
        REGISTRY_MAP.put(indexClass, sortedList);
    }

    /**
     * Checks if the method does not meet the requirements to be used to receive event calls from the Dispatcher.
     * Performed checks: Checks if the parameter length is not 1 and if the EventTarget annotation is not present.
     *
     * @param method Method to check.
     * @return True if the method should not be used for receiving event calls from the Dispatcher.
     * @see EventTarget
     */
    private static boolean isMethodBad(Method method) {
        return method.getParameterTypes().length != 1 || !method.isAnnotationPresent(EventTarget.class);
    }

    /**
     * Checks if the method does not meet the requirements to be used to receive event calls from the Dispatcher.
     * Performed checks: Checks if the parameter class of the method is the same as the event we want to receive.
     *
     * @param method     Method to check.
     * @param eventClass of the Event we want to find a method for receiving it.
     * @return True if the method should not be used for receiving event calls from the Dispatcher.
     * @see EventTarget
     */
    private static boolean isMethodBad(Method method, Class<? extends Event> eventClass) {
        return isMethodBad(method) || !method.getParameterTypes()[0].equals(eventClass);
    }

    /**
     * Call's an event and invokes the right methods that are listening to the event call.
     * First get's the matching list from the registry map based on the class of the event.
     * Then it checks if the list is not null. After that it will check if the event is an instance of
     * EventStoppable and if so it will add an extra check when looping trough the data.
     * If the Event was an instance of EventStoppable it will check every loop if the EventStoppable is stopped, and if
     * it is it will break the loop, thus stopping the call.
     * For every MethodData in the list it will invoke the Data's method with the Event as the argument.
     * After that is all done it will return the Event.
     *
     * @param event Event to dispatch.
     * @return Event in the state after dispatching it.
     */
    public static Event call(final Event event) {
        List<MethodData> dataList = REGISTRY_MAP.get(event.getClass());
        if (dataList != null) {
            /* A withheld packet is the commonest cause of a server correction,
               and this loop is the only place that knows which listener caused
               it -- afterwards the event says it was cancelled but not by whom.
               Watching for the transition attributes it exactly, for every
               module, without a single module being written to report itself.
               It costs one comparison per listener, on packet events only. */
            final boolean watched = event instanceof PacketEvent;
            if (event instanceof EventStoppable) {
                EventStoppable stoppable = (EventStoppable) event;
                for (final MethodData data : dataList) {
                    if (data.skipped()) {
                        continue;
                    }
                    boolean before = watched && ((PacketEvent) event).isCancelled();
                    invoke(data, event);
                    if (watched && !before && ((PacketEvent) event).isCancelled()) {
                        noteCancel(data, (PacketEvent) event);
                    }
                    if (stoppable.isStopped()) {
                        break;
                    }
                }
            } else {
                for (final MethodData data : dataList) {
                    if (data.skipped()) {
                        continue;
                    }
                    boolean before = watched && ((PacketEvent) event).isCancelled();
                    invoke(data, event);
                    if (watched && !before && ((PacketEvent) event).isCancelled()) {
                        noteCancel(data, (PacketEvent) event);
                    }
                }
            }
        }
        return event;
    }

    private static void noteCancel(MethodData data, PacketEvent event) {
        Object source = data.getSource();
        if (!(source instanceof Module)) {
            return;
        }
        /* Cancelling something cosmetic is not evidence of anything; counting
           it names whoever cancels most often rather than whoever caused the
           correction. */
        if (!ActionLedger.cancelMatters(event.getPacket())) {
            return;
        }
        ActionLedger.note(((Module) source).getName(),
                ActionLedger.holdKind(event.getPacket(), event.getType() == EventType.SEND));
    }

    /**
     * Invokes a MethodData when an Event call is made.
     *
     * @param data     The data of which the targeted Method should be invoked.
     * @param argument The called Event which should be used as an argument for the targeted Method.
     */
    private static void invoke(MethodData data, Event argument) {
        long start = System.nanoTime();
        try {
            data.getTarget().invoke(data.getSource(), argument);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            /* Same handling as before -- logged, and the dispatch carries on
               to the next handler -- but with the handler named first: a bare
               InvocationTargetException trace says only that reflection was
               involved. */
            System.err.println("[Myau] event handler " + data.getSource().getClass().getSimpleName()
                    + "." + data.getTarget().getName() + "(" + argument.getClass().getSimpleName()
                    + ") threw:");
            e.printStackTrace();
        }
        data.record(System.nanoTime() - start);
    }

    /**
     * Where the time in handlers went since the last call, most first, and
     * the counters reset: one line per handler that ran, then one per event
     * type. Racy across threads by design -- a profile, not an account.
     */
    public static List<String> takeProfile(double seconds, int top) {
        List<MethodData> all = new ArrayList<MethodData>();
        Map<String, long[]> byEvent = new HashMap<String, long[]>();
        for (Map.Entry<Class<? extends Event>, List<MethodData>> entry : REGISTRY_MAP.entrySet()) {
            for (MethodData data : entry.getValue()) {
                if (data.calls == 0) {
                    continue;
                }
                all.add(data);
                long[] sum = byEvent.get(entry.getKey().getSimpleName());
                if (sum == null) {
                    sum = new long[2];
                    byEvent.put(entry.getKey().getSimpleName(), sum);
                }
                sum[0] += data.nanos;
                sum[1] += data.calls;
            }
        }
        Collections.sort(all, new Comparator<MethodData>() {
            @Override
            public int compare(MethodData a, MethodData b) {
                return Long.compare(b.nanos, a.nanos);
            }
        });
        List<String> lines = new ArrayList<String>();
        long total = 0L;
        for (MethodData data : all) {
            total += data.nanos;
        }
        lines.add(String.format("handlers %.2f ms/s in total", total / 1e6 / seconds));
        for (int i = 0; i < Math.min(top, all.size()); i++) {
            MethodData data = all.get(i);
            lines.add(String.format("  %7.3f ms/s  %8d calls  avg %6.1f us  max %7.1f us  %s.%s(%s)",
                    data.nanos / 1e6 / seconds, data.calls, data.nanos / 1e3 / data.calls, data.maxNanos / 1e3,
                    data.getSource().getClass().getSimpleName(), data.getTarget().getName(),
                    data.getTarget().getParameterTypes()[0].getSimpleName()));
        }
        List<Map.Entry<String, long[]>> events = new ArrayList<Map.Entry<String, long[]>>(byEvent.entrySet());
        Collections.sort(events, new Comparator<Map.Entry<String, long[]>>() {
            @Override
            public int compare(Map.Entry<String, long[]> a, Map.Entry<String, long[]> b) {
                return Long.compare(b.getValue()[0], a.getValue()[0]);
            }
        });
        lines.add("by event:");
        for (Map.Entry<String, long[]> entry : events) {
            lines.add(String.format("  %7.3f ms/s  %8d calls  %s",
                    entry.getValue()[0] / 1e6 / seconds, entry.getValue()[1], entry.getKey()));
        }
        for (MethodData data : all) {
            data.reset();
        }
        return lines;
    }

    /**
     * @author DarkMagician6
     * @since January 2, 2014
     */
    private static final class MethodData {
        private final Object source;
        private final Method target;
        private final byte priority;
        /** The module to ask, when a disabled one's handler is skipped; null otherwise. */
        private final myau.module.Module skipWhenOff;
        /* The profile (takeProfile). Plain fields: written from the client and
           network threads alike, and a lost update costs nothing. */
        private long nanos;
        private long calls;
        private long maxNanos;

        /**
         * Sets the values of the data.
         *
         * @param source   The source Object of the data. Used by the VM to
         *                 determine to which object it should send the call to.
         * @param target   The targeted Method to which the Event should be send to.
         * @param priority The priority of this Method. Used by the registry to sort
         *                 the data on.
         */
        public MethodData(Object source, Method target, byte priority, boolean whenDisabled) {
            this.source = source;
            this.target = target;
            this.priority = priority;
            this.skipWhenOff = !whenDisabled && source instanceof myau.module.Module
                    ? (myau.module.Module) source : null;
        }

        /** A switched-off module's handler that does nothing while it is off. */
        boolean skipped() {
            return this.skipWhenOff != null && !this.skipWhenOff.isEnabled();
        }

        void record(long elapsed) {
            this.nanos += elapsed;
            this.calls++;
            if (elapsed > this.maxNanos) {
                this.maxNanos = elapsed;
            }
        }

        void reset() {
            this.nanos = 0L;
            this.calls = 0L;
            this.maxNanos = 0L;
        }

        /**
         * Gets the source Object of the data.
         *
         * @return Source Object of the targeted Method.
         */
        public Object getSource() {
            return source;
        }

        /**
         * Gets the targeted Method.
         *
         * @return The Method that is listening to certain Event calls.
         */
        public Method getTarget() {
            return target;
        }

        /**
         * Gets the priority value of the targeted Method.
         *
         * @return The priority value of the targeted Method.
         */
        public byte getPriority() {
            return priority;
        }
    }
}
