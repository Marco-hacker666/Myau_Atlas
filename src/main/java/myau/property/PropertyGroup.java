package myau.property;

import java.util.List;

/**
 * A module field that stands for several settings at once (2026-09-28).
 *
 * Module settings are found by reading each module's fields for Property
 * values (Myau's start-up loop). A field that is a PropertyGroup has its
 * members registered the same way, in order, so a composite setting -- a
 * min-max range -- is two ordinary settings to everything else: every
 * menu draws them, the config saves them, overrides apply to each.
 */
public interface PropertyGroup {
    /** The settings to register for this field; empty when they are registered as fields of their own. */
    List<Property<?>> properties();
}
