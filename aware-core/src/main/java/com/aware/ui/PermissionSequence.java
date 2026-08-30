package com.aware.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, Android-free core of {@link PermissionsHandler}'s request sequencing. Given the permissions a
 * sensor needs and a way to ask whether each is currently granted, it decides which permission to
 * prompt for next — skipping any already granted, including a group sibling the OS granted alongside
 * another (e.g. COARSE once FINE is allowed) — and, once finished, whether the requesting service may
 * be restarted.
 *
 * Extracted from the Activity so the two rules that matter can be unit-tested without the framework:
 * every permission is prompted at most once and already-granted ones never again, and a denial never
 * asks the caller to restart — the restart-after-denial is exactly what spins the unbreakable "Allow ..."
 * prompt loop this guards against.</p>
 */
public class PermissionSequence {

    /** Answers whether a permission is currently granted (backed by the OS in production, faked in tests). */
    public interface GrantChecker {
        boolean isGranted(String permission);
    }

    private final List<String> permissions;
    private final GrantChecker checker;
    private int index = 0;
    private boolean anyDenied = false;

    public PermissionSequence(List<String> permissions, GrantChecker checker) {
        this.permissions = permissions != null ? new ArrayList<>(permissions) : new ArrayList<String>();
        this.checker = checker;
    }

    /**
     * The next permission to prompt for, or {@code null} when none remain. Advances past — and so never
     * returns — any permission already granted, which is what makes granting one member of a permission
     * group (the OS reports the siblings as granted too) skip prompting for those siblings.
     */
    public String nextToRequest() {
        while (index < permissions.size() && checker.isGranted(permissions.get(index))) {
            index++;
        }
        return index < permissions.size() ? permissions.get(index) : null;
    }

    /** Record the system result for the permission at the current index and advance to the next. */
    public void onResult(boolean granted) {
        if (!granted) anyDenied = true;
        index++;
    }

    /** Record that the user skipped ("Not now") the current permission and advance. */
    public void onSkipped() {
        anyDenied = true;
        index++;
    }

    /** Decline every remaining item, used when the participant cancels the permission flow. */
    public void cancelRemaining() {
        if (index < permissions.size()) anyDenied = true;
        index = permissions.size();
    }

    /** True once every permission has been handled (all either granted or advanced past). */
    public boolean isDone() {
        return nextToRequest() == null;
    }

    public boolean anyDenied() {
        return anyDenied;
    }

    /**
     * Whether the requesting service should be restarted now the sequence is finished. Only when every
     * permission ended up granted: restarting after a denial makes the service find the permission still
     * missing and relaunch the handler, an unbreakable prompt loop.
     */
    public boolean shouldRestartService() {
        return !anyDenied;
    }

    /** What the handler should do with the current permission once a system result comes back. */
    public enum ResultAction {
        /** Record the outcome (a grant, or a denial the OS will still re-prompt) and move on. */
        ADVANCE,
        /** The permission is blocked — asking again is a no-op — so offer the app-settings route. */
        PROMPT_SETTINGS
    }

    /**
     * Given a system permission result, decide whether to just advance the sequence or offer the
     * app-settings route. A grant, or a denial the OS will still let us re-prompt (rationale allowed),
     * simply advances. A denial the OS will not re-prompt — blocked via "don't ask again" or a second
     * denial, or already blocked so no dialog even appeared — means requesting again is a silent no-op,
     * so a user who asked to allow it has to be sent to app settings to grant it by hand.
     */
    public static ResultAction actionAfterResult(boolean granted, boolean shouldShowRationale) {
        if (granted) return ResultAction.ADVANCE;
        return shouldShowRationale ? ResultAction.ADVANCE : ResultAction.PROMPT_SETTINGS;
    }
}
