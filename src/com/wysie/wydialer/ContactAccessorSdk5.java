package com.wysie.wydialer;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.CommonDataKinds.Phone;

final class ContactAccessorSdk5 extends ContactAccessor {
	ContentResolver myContentResolver;
	final MyContactSplit myContactSplit;

	public ContactAccessorSdk5() {
		myContactSplit = new MyContactSplit();
	}
	
	@Override
	public IContactSplit getContactSplit() {
		return myContactSplit;
	}

	private static final String[] PHONE_PROJECTION = new String[] {
		Phone._ID, Phone.NUMBER, Phone.IS_SUPER_PRIMARY };
	private static final String PRIMARY_PHONE_QUERY =
		Phone.CONTACT_ID + " = ?";
	private static final String PHONE_QUERY_SORT = Phone.IS_SUPER_PRIMARY;

	class MyContactSplit implements IContactSplit {
		@Override
		public Uri getCallUri(Uri lookupUri) {
			// 'uri' is a contact URI. we need to initiate a call to the preferred number (if any)
			// or move to an activity for the user to choose what number to call.
			long id = ContentUris.parseId(lookupUri);
			boolean foundit = false;
			long phone_id = 0;

			// Step 1: Look for an IS_SUPER_PRIMARY phone or a single phone.
			Cursor cursor = myContentResolver.query(Phone.CONTENT_URI, PHONE_PROJECTION, 
				PRIMARY_PHONE_QUERY, new String[] { Long.toString(id) }, PHONE_QUERY_SORT);
			try {
				if (cursor.moveToFirst() && (cursor.getInt(2) != 0 || cursor.isLast())) {
					foundit = true;
					phone_id = cursor.getLong(0);
				}
			} finally {
				cursor.close();
			}
			
			if (foundit) {
				return ContentUris.withAppendedId(Phone.CONTENT_URI, phone_id);
			} else {
				// Can't figure out what number to call.
				return null;
			}
		}

		@Override
		public Uri getContactUri(Uri lookupUri) {
			return lookupUri;
		}

		@Override
		public String getDisplayName(Cursor c) {
			return c.getString(2);
		}

		@Override
		public Uri getLookupUri(Cursor c) {
			long id = c.getLong(0);
			String lookup_key = c.getString(1);

			return Contacts.getLookupUri(id, lookup_key);
		}

	}
	
	private final static String upName = "UPPER(" + Contacts.DISPLAY_NAME + ")";
	/*
	private final static String peopleSql = 
		"(" + upName + " GLOB ? OR " + upName + " GLOB ?) AND " +
		"has_phone_number = 1";*/
	private final static String peopleSql = 
		"(" + upName + " GLOB ? OR " + upName + " GLOB ? OR " + Phone.NUMBER + " GLOB ?)";

	private static final String[] PEOPLE_PROJECTION = new String[] {
		Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME };
	private static final String PEOPLE_SORT = Contacts.DISPLAY_NAME +
	" COLLATE LOCALIZED ASC";
	
	private static final String[] PEOPLE_PHONE_PROJECTION = new String[] {
		Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL };
	
	@Override
	public Cursor recalculate(String filter) {
		String[] args = new String[] { filter + "*", "*[ ]" + filter + "*", filter + "*" };
		return myContentResolver.query(Phone.CONTENT_URI, PEOPLE_PHONE_PROJECTION, peopleSql, args, PEOPLE_SORT);
		//return myContentResolver.query(Contacts.CONTENT_URI, PEOPLE_PROJECTION, peopleSql, args, PEOPLE_SORT);
	}

	@Override
	public void setContentResolver(ContentResolver cr) {
		myContentResolver = cr;
	}
}
