package com.atenea.remoteworker;

public interface DevelopmentChangeBranchPublicationGateway {
    DevelopmentChangeBranchPublication publish(
            DevelopmentChangeBranchPublicationCommand command);
}
