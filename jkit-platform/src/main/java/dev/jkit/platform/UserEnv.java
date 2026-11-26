package dev.jkit.platform;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * The user's own environment variables, the ones every program they start from now on gets
 * (Windows: {@code HKCU\Environment}). Values are raw: a {@code %VAR%} in them stays as written.
 */
public interface UserEnv {

    /** The values of {@code names} that are set; a name that isn't set is left out. */
    Map<String, String> read(Collection<String> names) throws IOException;

    /**
     * The system-wide PATH, with its {@code %VAR%}s expanded: Windows puts it before the user's, so
     * a {@code java} in it wins over one in the user PATH.
     */
    String systemPath() throws IOException;

    /**
     * Sets each variable (a null value deletes it), keeping an existing value's kind (a PATH with
     * {@code %USERPROFILE%} in it stays expandable), then tells running programs, such as Explorer,
     * so the programs they start get the new values.
     */
    void write(Map<String, String> values) throws IOException;
}
