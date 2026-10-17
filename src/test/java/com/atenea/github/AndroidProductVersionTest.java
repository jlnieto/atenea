package com.atenea.github;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AndroidProductVersionTest {
    private final String source="android {\n    versionCode = 160\n    versionName = \"0.5.127\"\n}\n";
    @Test void derivesSingleNormalPatchAndChangesOnlyThoseTwoLiterals() {
        var version=AndroidProductVersion.read(source);
        var next=version.nextAfter(new AndroidProductVersion(160,"0.5.127"));
        assertEquals(new AndroidProductVersion(161,"0.5.128"),next);
        assertEquals(source.replace("160","161").replace("0.5.127","0.5.128"),version.replace(source,next));
    }
    @Test void rejectsMissingAmbiguousMalformedAndArbitraryVersionInputs() {
        for (String value:new String[]{"",source+"versionCode = 161\n",source+"versionName = \"0.5.128\"\n",
                source.replace("160","0"),source.replace("0.5.127","0.6.30-extra"),source.replace("0.5.127","00.5.127")})
            assertThrows(GitHubIntegrationException.class,()->AndroidProductVersion.read(value));
        assertThrows(GitHubIntegrationException.class,()->new AndroidProductVersion(Integer.MAX_VALUE,"0.5.127"));
        assertThrows(GitHubIntegrationException.class,()->AndroidProductVersion.read(source).nextAfter(new AndroidProductVersion(160,"0.6.30")));
    }
    @Test void doesNotAcceptCallerChosenJumpsOrBuildCounterVersions() {
        var version=AndroidProductVersion.read(source);
        for (var next:new AndroidProductVersion[]{new AndroidProductVersion(160,"0.5.127"),
                new AndroidProductVersion(162,"0.5.129"),new AndroidProductVersion(1000030,"0.6.30")})
            assertThrows(GitHubIntegrationException.class,()->version.replace(source,next));
    }
}
