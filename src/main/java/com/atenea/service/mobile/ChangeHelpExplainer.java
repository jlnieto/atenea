package com.atenea.service.mobile;

import com.atenea.api.mobile.ChangeHelpResponse;

/** Optional prose only; has no execution tools or action authority. */
public interface ChangeHelpExplainer {
    boolean available();
    String explain(ChangeHelpContext context, String question, ChangeHelpResponse guidance) throws Exception;
}
