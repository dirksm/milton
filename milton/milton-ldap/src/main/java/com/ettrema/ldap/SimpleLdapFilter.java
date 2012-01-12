package com.ettrema.ldap;

import com.ettrema.common.LogUtils;
import java.io.IOException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author brad
 */
class SimpleLdapFilter implements LdapFilter {
	private static final Logger log = LoggerFactory.getLogger(SimpleLdapFilter.class);
	
	final UserFactory userFactory;
	
	static final String STAR = "*";
	final String attributeName;
	final String value;
	final int mode;
	final int operator;
	final boolean canIgnore;

	SimpleLdapFilter(UserFactory userFactory, String attributeName) {
		this.userFactory = userFactory;
		this.attributeName = attributeName;
		this.value = SimpleLdapFilter.STAR;
		this.operator = Ldap.LDAP_FILTER_SUBSTRINGS;
		this.mode = 0;
		this.canIgnore = checkIgnore();
	}

	SimpleLdapFilter(UserFactory userFactory, String attributeName, String value, int ldapFilterOperator, int ldapFilterMode) {
		this.userFactory = userFactory;
		this.attributeName = attributeName;
		this.value = value;
		this.operator = ldapFilterOperator;
		this.mode = ldapFilterMode;
		this.canIgnore = checkIgnore();
	}

	private boolean checkIgnore() {
		if ("objectclass".equals(attributeName) && STAR.equals(value)) {
			// ignore cases where any object class can match
			return true;
//		} else if (LdapConnection.CRITERIA_MAP.get(attributeName) == null && LdapUtils.getContactAttributeName(attributeName) == null) {
//			log.debug("LOG_LDAP_UNSUPPORTED_FILTER_ATTRIBUTE", attributeName, value);
//			return true;
		}
		return false;
	}

	@Override
	public boolean isFullSearch() {
		// only (objectclass=*) is a full search
		return "objectclass".equals(attributeName) && STAR.equals(value);
	}

	@Override
	public String toString() {
		StringBuilder buffer = new StringBuilder();
		buffer.append('(');
		buffer.append(attributeName);
		buffer.append('=');
		if (SimpleLdapFilter.STAR.equals(value)) {
			buffer.append(SimpleLdapFilter.STAR);
		} else if (operator == Ldap.LDAP_FILTER_SUBSTRINGS) {
			if (mode == Ldap.LDAP_SUBSTRING_FINAL || mode == Ldap.LDAP_SUBSTRING_ANY) {
				buffer.append(SimpleLdapFilter.STAR);
			}
			buffer.append(value);
			if (mode == Ldap.LDAP_SUBSTRING_INITIAL || mode == Ldap.LDAP_SUBSTRING_ANY) {
				buffer.append(SimpleLdapFilter.STAR);
			}
		} else {
			buffer.append(value);
		}
		buffer.append(')');
		return buffer.toString();
	}

	@Override
	public Condition getContactSearchFilter() {
		String contactAttributeName = attributeName;
		if (canIgnore || (contactAttributeName == null)) {
			return null;
		}
		Condition condition = null;
		if (operator == Ldap.LDAP_FILTER_EQUALITY) {
			LogUtils.debug(log, "getContactSearchFilter: equality", value);
			condition = Conditions.isEqualTo(contactAttributeName, value);
		} else if ("*".equals(value)) {
			LogUtils.debug(log, "getContactSearchFilter: *");
			condition = Conditions.not(Conditions.isNull(contactAttributeName));
			// do not allow substring search on integer field imapUid
		} else if (!"imapUid".equals(contactAttributeName)) {
			// endsWith not supported by exchange, convert to contains
			if (mode == Ldap.LDAP_SUBSTRING_FINAL || mode == Ldap.LDAP_SUBSTRING_ANY) {
				LogUtils.debug(log, "getContactSearchFilter: contains", value);
				condition = Conditions.contains(contactAttributeName, value);
			} else {
				LogUtils.debug(log, "getContactSearchFilter: startswith", value);
				condition = Conditions.startsWith(contactAttributeName, value);
			}
		}
		return condition;
	}

	@Override
	public boolean isMatch(Map<String, String> person) {
		if (canIgnore) {
			// Ignore this filter
			return true;
		}
		String personAttributeValue = person.get(attributeName);
		if (personAttributeValue == null) {
			// No value to allow for filter match
			return false;
		} else if (value == null) {
			// This is a presence filter: found
			return true;
		} else if ((operator == Ldap.LDAP_FILTER_EQUALITY) && personAttributeValue.equalsIgnoreCase(value)) {
			// Found an exact match
			return true;
		} else if ((operator == Ldap.LDAP_FILTER_SUBSTRINGS) && (personAttributeValue.toLowerCase().indexOf(value.toLowerCase()) >= 0)) {
			// Found a substring match
			return true;
		}
		return false;
	}

	@Override
	public List<Contact> findInGAL(User user, Set<String> returningAttributes, int sizeLimit) throws IOException {
		if (canIgnore) {
			return null;
		}
		String contactAttributeName = attributeName;
		if (contactAttributeName != null) {
			// quick fix for cn=* filter
			List<Contact> galPersons = userFactory.galFind(Conditions.startsWith(contactAttributeName, "*".equals(value) ? "A" : value), LdapUtils.convertLdapToContactReturningAttributes(returningAttributes), sizeLimit);
			if (operator == Ldap.LDAP_FILTER_EQUALITY) {
				// Make sure only exact matches are returned
				Map<String, Contact> results = new HashMap<String, Contact>();
				List<Contact> list = new ArrayList<Contact>();
				for (Contact person : galPersons) {
					if (isMatch(person)) {
						// Found an exact match
						list.add(person);
					}
				}
				return list;
			} else {
				return galPersons;
			}
		}
		return null;
	}

	@Override
	public void add(LdapFilter filter) {
		// Should never be called
		log.error("LOG_LDAP_UNSUPPORTED_FILTER", "nested simple filters");
	}
	
}