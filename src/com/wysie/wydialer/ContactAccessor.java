/*
 * Copyright (C) 2010 Lawrence Greenfield
 * 
 *  SpellDial is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  SpellDial is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with SpellDial.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
/* Derived from
 * http://code.google.com/p/android-business-card/source/browse/trunk/android-business-card/BusinessCard/src/com/example/android/businesscard/ContactAccessor.java
 *
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wysie.wydialer;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.content.ContentUris;

import android.net.Uri;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Intents.Insert;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.util.Log;

final class ContactAccessor {
  ContentResolver myContentResolver;
  final MyContactSplit myContactSplit;

  public ContactAccessor(ContentResolver cr) {
    myContactSplit = new MyContactSplit();
    myContentResolver = cr;
  }

  public IContactSplit getContactSplit() {
    return myContactSplit;
  }

  public Intent getCallLogIntent() {
    Intent intent = new Intent(Intent.ACTION_VIEW, null);
    intent.setType("vnd.android.cursor.dir/calls");
    return intent;
  }

  public Intent getContactsIntent() {
    Intent i = new Intent();
    i.setAction(Intent.ACTION_VIEW);
    i.setData(android.provider.ContactsContract.Contacts.CONTENT_URI);
    return i;
  }

  public Intent getFavouritesIntent() {
    return null;
  }

  private static final String[] PHONE_PROJECTION = new String[] { Phone._ID,
      Phone.NUMBER, Phone.IS_PRIMARY };
  private static final String PRIMARY_PHONE_QUERY = Phone.CONTACT_ID + " = ?";
  private static final String PHONE_QUERY_SORT = Phone.IS_SUPER_PRIMARY;

  class MyContactSplit implements IContactSplit {
    @Override
    public Uri getCallUri(Uri lookupUri) {
      // 'uri' is a contact URI. we need to initiate a call to the preferred
      // number (if any)
      // or move to an activity for the user to choose what number to call.
      long id = ContentUris.parseId(lookupUri);
      boolean foundit = false;
      long phone_id = 0;

      // Step 1: Look for an IS_SUPER_PRIMARY phone or a single phone.
      Cursor cursor = myContentResolver.query(Phone.CONTENT_URI,
	  PHONE_PROJECTION, PRIMARY_PHONE_QUERY, 
	  new String[] { Long.toString(id) }, PHONE_QUERY_SORT);
      try {

	cursor.moveToFirst();
	Log.i("getCallUri", String.format("number: %s  pri: %d", cursor
	    .getString(1), cursor.getInt(2)));

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
   * private final static String peopleSql = "(" + upName + " GLOB ? OR " +
   * upName + " GLOB ?) AND " + "has_phone_number = 1";
   */
  private final static String peopleSql = "(" + upName + " GLOB ? OR " + upName
      + " GLOB ? OR REPLACE(" + Phone.NUMBER
      + ",'-', '') GLOB ?) AND (is_primary = 1)";

  private static final String PEOPLE_SORT = Contacts.DISPLAY_NAME
      + " COLLATE LOCALIZED ASC";

  private static final String[] PEOPLE_PHONE_PROJECTION = new String[] {
      Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME, Phone.NUMBER,
      Phone.TYPE, Phone.LABEL, Contacts.PHOTO_ID, Phone.IS_PRIMARY };

  public Cursor recalculate(String filter, boolean matchAnywhere) {
    String[] args = null;
    if (matchAnywhere) {
      args = new String[] { filter + "*", "*[ ]" + filter + "*",
	  "*" + filter + "*" };
    } else {
      args = new String[] { filter + "*", "*[ ]" + filter + "*", filter + "*" };
    }

    return myContentResolver.query(Phone.CONTENT_URI, PEOPLE_PHONE_PROJECTION,
	peopleSql, args, PEOPLE_SORT);
  }

  public void setContentResolver(ContentResolver cr) {
    myContentResolver = cr;
  }

  public Intent addToContacts(String number) {
    Intent intent = new Intent(Intent.ACTION_INSERT_OR_EDIT);
    intent.putExtra(Insert.PHONE, number);
    intent.setType(Contacts.CONTENT_ITEM_TYPE);
    return intent;
  }
}
