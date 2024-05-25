package gov.cdc.izgateway.db.model;

import java.io.Serializable;

import org.apache.commons.lang3.StringUtils;

import gov.cdc.izgateway.model.IAccessControlId;

@SuppressWarnings("serial")
public class AccessControlId implements Serializable, IAccessControlId {
    private String category;
    private String name;
    private String member;
    
    public AccessControlId(String category, String name, String member) {
    	this.category = category;
    	this.name = name;
    	this.member = member;
    }
    @Override
    public boolean equals(Object o) {
    	if (!(o instanceof AccessControlId)) {
    		return false;
    	}
    	AccessControlId that = (AccessControlId)o;
    	return StringUtils.equals(this.category, that.category) &&
    	       StringUtils.equals(this.member, that.member) &&
    	       StringUtils.equals(this.name, that.name);
    }
    
    @Override 
    public int hashCode() {
    	return (category == null ? 0 : category.hashCode()) ^ 
    		   (member == null ? 0 : member.hashCode()) ^
    		   (name == null ? 0 : name.hashCode());
    }
    
    @Override
	public String getCategory() {
		return category;
	}
	@Override
	public void setCategory(String category) {
		this.category = category;
	}
	@Override
	public String getName() {
		return name;
	}
	@Override
	public void setName(String name) {
		this.name = name;
	}
	@Override
	public String getMember() {
		return member;
	}
	@Override
	public void setMember(String member) {
		this.member = member;
	}
	public AccessControlId() {
    	
    }
}