package be.nabu.libs.triton;

import java.util.UUID;

public class TritonMessage {
	private String type, payload, id, conversationId;
	private int version = 1;
	public String getType() {
		return type;
	}
	public void setType(String type) {
		this.type = type;
	}
	public String getPayload() {
		return payload;
	}
	public void setPayload(String payload) {
		this.payload = payload;
	}
	public int getVersion() {
		return version;
	}
	public void setVersion(int version) {
		this.version = version;
	}
	public String getId() {
		if (id == null) {
			id = UUID.randomUUID().toString().replace("-", "");
		}
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}
	public String getConversationId() {
		if (conversationId == null) {
			conversationId = getId();
		}
		return conversationId;
	}
	public void setConversationId(String conversationId) {
		this.conversationId = conversationId;
	}
}
