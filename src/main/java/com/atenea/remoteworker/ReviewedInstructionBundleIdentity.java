package com.atenea.remoteworker;

import com.atenea.persistence.worksession.AgentRunEntity;

public final class ReviewedInstructionBundleIdentity {
    public static final String REVISION = "atenea-reviewed-instruction-bundle-v1";
    public static final String PLATFORM_SHA256 =
            "44c578a286eb50b35612be0b6c38d59a503e6fee1ecf6cd0339415af018cdf0d";
    public static final String PROJECT_PATH = "AGENTS.md";
    public static final String ATENEA_PROJECT_SHA256 =
            "a09adc5855ff54490211a0f5c82f413cb84ee7197b2b350e0b0dc40eba7c98dc";
    public static final String ATENEA_BUNDLE_SHA256 =
            "ab9f1877c83333945497797e6b8aefd20f67debf8e3bdc6d1b824fc5a3f86c04";
    public static final String BEAUTIPS_PROJECT_SHA256 =
            "0e06aa861b11e324610f3a7cd7aef1bff3c2712d7b838a052bb5748542c8e1c7";
    public static final String BEAUTIPS_BUNDLE_SHA256 =
            "6e5affe84ca7e300c1c3f0907056013820999699d84fd0e491add924ad685b60";

    private ReviewedInstructionBundleIdentity() {
    }

    public static void apply(AgentRunEntity run, String projectIdentity) {
        run.setInstructionBundleRevision(REVISION);
        run.setInstructionBundleSha256(bundleSha256(projectIdentity));
        run.setPlatformInstructionSha256(PLATFORM_SHA256);
        run.setProjectInstructionPath(PROJECT_PATH);
        run.setProjectInstructionSha256(projectSha256(projectIdentity));
    }

    public static boolean matches(AgentRunEntity run) {
        if (run == null || run.getProjectIdentity() == null) {
            return false;
        }
        try {
            return REVISION.equals(run.getInstructionBundleRevision())
                    && bundleSha256(run.getProjectIdentity())
                        .equals(run.getInstructionBundleSha256())
                    && PLATFORM_SHA256.equals(run.getPlatformInstructionSha256())
                    && PROJECT_PATH.equals(run.getProjectInstructionPath())
                    && projectSha256(run.getProjectIdentity())
                        .equals(run.getProjectInstructionSha256());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String bundleSha256(String projectIdentity) {
        return switch (projectIdentity) {
            case ProjectCodexIdentity.PROJECT_IDENTITY -> ATENEA_BUNDLE_SHA256;
            case BeautipsProjectCodexIdentity.PROJECT_IDENTITY -> BEAUTIPS_BUNDLE_SHA256;
            default -> throw new IllegalArgumentException("Unsupported instruction project");
        };
    }

    private static String projectSha256(String projectIdentity) {
        return switch (projectIdentity) {
            case ProjectCodexIdentity.PROJECT_IDENTITY -> ATENEA_PROJECT_SHA256;
            case BeautipsProjectCodexIdentity.PROJECT_IDENTITY -> BEAUTIPS_PROJECT_SHA256;
            default -> throw new IllegalArgumentException("Unsupported instruction project");
        };
    }
}
