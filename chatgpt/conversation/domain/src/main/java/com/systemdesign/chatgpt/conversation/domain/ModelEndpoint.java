package com.systemdesign.chatgpt.conversation.domain;

public interface ModelEndpoint extends ModelGateway {
    ModelProfile profile();

    boolean healthy();
}
