package com.atenea.github;

import java.util.regex.Pattern;

/** Committed product version, not a CI counter or a build-time override. */
public record AndroidProductVersion(int code, String name) {
    private static final Pattern CODE = Pattern.compile("(?m)^(\\s*versionCode\\s*=\\s*)([0-9]+)(\\s*)$");
    private static final Pattern NAME = Pattern.compile("(?m)^(\\s*versionName\\s*=\\s*\")([0-9]+\\.[0-9]+\\.[0-9]+)(\"\\s*)$");
    public AndroidProductVersion {
        if (code < 1 || code >= 2_100_000_000 || name == null
                || !name.matches("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})")) reject();
    }
    public static AndroidProductVersion read(String source) {
        if (source == null || source.length() > 100_000) return reject();
        var code = CODE.matcher(source); var name = NAME.matcher(source);
        if (!code.find() || !name.find()) return reject();
        AndroidProductVersion result;
        try { result = new AndroidProductVersion(Integer.parseInt(code.group(2)), name.group(2)); }
        catch (NumberFormatException invalid) { return reject(); }
        if (code.find() || name.find()) return reject();
        return result;
    }
    public AndroidProductVersion nextAfter(AndroidProductVersion channel) {
        String prefix = name.substring(0, name.lastIndexOf('.') + 1);
        if (!channel.name.startsWith(prefix)) return reject();
        int patch = Math.max(Integer.parseInt(name.substring(prefix.length())),
                Integer.parseInt(channel.name.substring(prefix.length()))) + 1;
        return new AndroidProductVersion(Math.addExact(Math.max(code, channel.code), 1), prefix + patch);
    }
    public String replace(String source, AndroidProductVersion next) {
        if (!equals(read(source)) || next.code <= code || !next.equals(nextAfter(this))) return reject();
        return NAME.matcher(CODE.matcher(source).replaceFirst(match -> match.group(1) + next.code + match.group(3)))
                .replaceFirst(match -> match.group(1) + next.name + match.group(3));
    }
    private static <T> T reject() { throw new GitHubIntegrationException("ANDROID_VERSION_POLICY_REJECTED"); }
}
