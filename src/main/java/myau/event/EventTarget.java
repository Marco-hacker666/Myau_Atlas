package myau.event;

import myau.event.types.Priority;

import java.lang.annotation.*;

/**
 * Marks a method so that the EventManager knows that it should be registered.
 * The priority of the method is also set with this.
 *
 * @author DarkMagician6
 * @see Priority
 * @since July 30, 2013
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface EventTarget {
    byte value() default Priority.MEDIUM;

    /**
     * Whether a module's handler is called while the module is switched off.
     *
     * Off by default: the dispatcher skips a disabled module's handlers,
     * which is what nearly all of them do first anyway. Set on the ones that
     * do something regardless -- resets on a world change, bookkeeping that
     * must not have gaps, cancelling input while a state lasts. Those were
     * found and marked one by one (2026-09-25): a handler counts as
     * skippable only if its body provably does nothing when disabled.
     */
    boolean whenDisabled() default false;
}
