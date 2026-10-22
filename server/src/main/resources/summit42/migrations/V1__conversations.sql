-- The app builder's conversation about one app (Conversation), whose id is <organizationId>:<applicationId>
CREATE TABLE IF NOT EXISTS summit42_conversation (
    messages OBJECT (role KEYWORD, text TEXT) NOT INDEXED
);
