/*
 * Copyright (C) 2010 Wysie Soh
 * 
 * NubDial is free software. It is based upon Lawrence's Greenfield's SpellDial
 * and as such, is under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 2 of the License, or (at your option)
 * any later version.
 * 
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
// Features:
// - figure out when to clear the partially completed number
// - audible touch tones
//
// Display:
// - make landspace mode pretty?
// - make icon pretty
// - add number so far to the ListView?
// - show pictures?
//
// Bugs:
// - occasional crash in landspace mode when returning from a call
// - figure out how to deal with accents
//
// Performance if we want the types of phones avail:
// - suck the whole thing into memory? would suck for lots of contacts...
// - do a join and have a non 1:1 mapping from results to rows?

package com.wysie.wydialer;

import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.telephony.PhoneNumberFormattingTextWatcher;
import android.telephony.TelephonyManager;
import android.text.method.DialerKeyListener;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.ContextMenu;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.QuickContactBadge;
import android.widget.TextView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ResourceCursorAdapter;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.View.OnClickListener;
import android.view.View.OnCreateContextMenuListener;
import android.view.View.OnLongClickListener;
import android.view.View.OnTouchListener;

import java.util.ArrayList;
public class PhoneSpellDialer extends Activity implements OnScrollListener, OnClickListener, OnLongClickListener, /* OnCreateContextMenuListener, */ OnItemClickListener {
	private static final String TAG = "SpellDial";
	
	// Identifiers for our menu items.
	private static final int ADD_TO_CONTACTS = 0;
	private static final int CALL_LOG = 1;
	private static final int CONTACTS = 2;
	private static final int FAVOURITES = 3;
    private static final int SETTINGS_ID = 4;
    
    //Identifiers for context menus
    private static final int SMS = 0;
    	
	private ToneGenerator mToneGenerator;
    private Object mToneGeneratorLock = new Object();
    private static final int TONE_LENGTH_MS = 150;
    private static final int TONE_RELATIVE_VOLUME = 80;
    private static final int DIAL_TONE_STREAM_TYPE = AudioManager.STREAM_MUSIC;
    
	private static boolean hideDialpadOnScroll, showContactPictures, matchAnywhere, mDTMFToneEnabled, matchedItalics, matchedBold, matchedDigits, matchedHighlight, noMatches = false;
	private Vibrator mVibrator;
    private boolean prefVibrateOn;
    private long[] mVibratePattern;
    private static final int VIBRATE_NO_REPEAT = -1;
    
    private static final StyleSpan ITALIC_STYLE = new StyleSpan(android.graphics.Typeface.ITALIC);
    private static final StyleSpan BOLD_STYLE = new StyleSpan(android.graphics.Typeface.BOLD);
    private static BackgroundColorSpan matchedHighlightColor;
    private static ForegroundColorSpan matchedDigitsColor;

    private Drawable mDigitsBackground;
    private Drawable mDigitsEmptyBackground;
	private EditText digitsView;
	private ImageButton dialButton, deleteButton;
	private ContactAccessor contactAccessor;
	private MenuItem mAddToContacts;
	
	private StringBuilder curFilter;
	private ContactListAdapter myAdapter;
	private ListView myContactList;
	
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

		setContentView(R.layout.main);
		
		contactAccessor = ContactAccessor.getInstance(getContentResolver());
		Cursor cur = contactAccessor.recalculate("", matchAnywhere);
		startManagingCursor(cur);
		myAdapter = new ContactListAdapter(this, cur, contactAccessor.getContactSplit());
		curFilter = new StringBuilder();
		
		Resources r = getResources();
        mDigitsBackground = r.getDrawable(R.drawable.btn_digits_activated);
        mDigitsEmptyBackground = r.getDrawable(R.drawable.btn_digits);		
		dialButton = (ImageButton) findViewById(R.id.dialButton);
		deleteButton = (ImageButton) findViewById(R.id.deleteButton);
		digitsView = (EditText) findViewById(R.id.digitsText);		
		myContactList = (ListView) findViewById(R.id.contactlist);
		myContactList.setOnCreateContextMenuListener(this);
		myContactList.setAdapter(myAdapter);
		setHandlers();
		setPreferences();
	}

	private void setPreferences() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		mDTMFToneEnabled = prefs.getBoolean("dial_enable_dial_tone",false);
		prefVibrateOn = prefs.getBoolean("dial_enable_haptic", false);
		
		matchedItalics = prefs.getBoolean("matched_italics", false);
		matchedBold = prefs.getBoolean("matched_bold", true);
		matchedDigits = prefs.getBoolean("matched_colour", false);
		matchedDigitsColor = new ForegroundColorSpan(Integer.parseInt(prefs.getString("matched_colour_choice", "-16777216")));
		matchedHighlight = prefs.getBoolean("matched_highlight", true);
		matchedHighlightColor = new BackgroundColorSpan(Integer.parseInt(prefs.getString("matched_highlight_choice", "-3355444")));
		matchAnywhere = prefs.getBoolean("match_num_sequence", true);
		showContactPictures = prefs.getBoolean("show_contact_pictures", true);
		hideDialpadOnScroll = prefs.getBoolean("auto_hide_dialpad_on_fling", true);
		initVibrationPattern();
		setDigitsColor(prefs);
		
		ImageButton digitOne = (ImageButton)findViewById(R.id.button1);		
		if (hasVoicemail()) {
        	digitOne.setImageResource(R.drawable.dial_num_1_with_vm);
        }
        else {
        	digitOne.setImageResource(R.drawable.dial_num_1_no_vm);
        }
	}

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
    	super.onCreateOptionsMenu(menu);
    	mAddToContacts = menu.add(0, ADD_TO_CONTACTS, 0, R.string.menu_new_contacts).setIcon(android.R.drawable.ic_menu_add);
    	menu.add(0, CALL_LOG, 0, R.string.menu_call_log).setIcon(R.drawable.ic_tab_unselected_recent);
    	menu.add(0, CONTACTS, 0, R.string.menu_contacts).setIcon(R.drawable.ic_tab_unselected_contacts);
    	//menu.add(0, FAVOURITES, 0, R.string.menu_favs).setIcon(R.drawable.ic_tab_unselected_starred);
    	// Consider using XML!
        menu.add(0, SETTINGS_ID, 0, R.string.menu_settings)
                .setIcon(android.R.drawable.ic_menu_preferences);
        
    	return true;
    }
    
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
    	if (digitsView.length() == 0) {
    		mAddToContacts.setTitle(R.string.menu_new_contacts);
    	}
    	else {
    		mAddToContacts.setTitle(R.string.menu_add_contacts);
    	}

        return true;
    }
    
    @Override
    protected void onResume() {
        super.onResume();

        // if the mToneGenerator creation fails, just continue without it.  It is
        // a local audio signal, and is not as important as the dtmf tone itself.
        synchronized(mToneGeneratorLock) {
            if (mToneGenerator == null) {
                try {
                    // we want the user to be able to control the volume of the dial tones
                    // outside of a call, so we use the stream type that is also mapped to the
                    // volume control keys for this activity
                    mToneGenerator = new ToneGenerator(DIAL_TONE_STREAM_TYPE, TONE_RELATIVE_VOLUME);
                    setVolumeControlStream(DIAL_TONE_STREAM_TYPE);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Exception caught while creating local tone generator: " + e);
                    mToneGenerator = null;
                }
            }
        }
        
        setPreferences();
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        
        synchronized(mToneGeneratorLock) {
            if (mToneGenerator != null) {
                mToneGenerator.release();
                mToneGenerator = null;
            }
        }
    }
    
    /*
    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {
    	menu.add(0, SMS, 0, R.string.context_sms);
    }
    
    @Override
    public boolean onContextItemSelected(MenuItem item) {
    	  AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
    	  switch (item.getItemId()) {
    	  case SMS:
    	    //Todo: SMS
    		  Cursor cursor = (Cursor)myAdapter.getItem(info.position);
    		  
    	    return true;
    	  default:
    	    return super.onContextItemSelected(item);
    	  }
    }
    */
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
    	switch (item.getItemId()) {
    	case ADD_TO_CONTACTS:
        	startActivity(contactAccessor.addToContacts(digitsView.getText().toString()));
    		break;
    	
        case SETTINGS_ID:
        	Intent launchPreferencesIntent = new Intent().setClass(
        			this, Preferences.class);            
            startActivity(launchPreferencesIntent);
        	break;
        	
        case CALL_LOG:
        	startActivity(contactAccessor.getCallLogIntent());
        	break;
        	
        case CONTACTS:
        	startActivity(contactAccessor.getContactsIntent());
        	break;
    	}
    	return super.onOptionsItemSelected(item);
    }
	
	private void setupButton(int id) {
		ImageButton button = (ImageButton) findViewById(id);
		button.setOnClickListener(this);
		button.setOnTouchListener(onTouchListener);
		
		if (id == R.id.button0 || id == R.id.button1 || id == R.id.deleteButton)
			button.setOnLongClickListener(this);
	}

	private void setHandlers() {
		setupButton(R.id.button0);
		setupButton(R.id.button1);
		setupButton(R.id.button2);
		setupButton(R.id.button3);
		setupButton(R.id.button4);
		setupButton(R.id.button5);
		setupButton(R.id.button6);
		setupButton(R.id.button7);
		setupButton(R.id.button8);
		setupButton(R.id.button9);
		setupButton(R.id.buttonstar);
		setupButton(R.id.buttonpound);
		setupButton(R.id.dialButton);
		setupButton(R.id.deleteButton);
		
		digitsView.setOnClickListener(this);
		digitsView.setKeyListener(DialerKeyListener.getInstance());
		digitsView.addTextChangedListener(new PhoneNumberFormattingTextWatcher());
		digitsView.setInputType(android.text.InputType.TYPE_NULL);

		ListView list = (ListView) findViewById(R.id.contactlist);
		list.setOnItemClickListener(this);
		list.setOnScrollListener(this);
		
		/*
		View keypad = findViewById(R.id.keypad);
		keypad.setClickable(true);
		keypad.setOnTouchListener(onTouchListener);
		
		View dialDelete = findViewById(R.id.dial_digits_delete);
		dialDelete.setOnTouchListener(onTouchListener);
		*/
		
		View digits = findViewById(R.id.digitsText);
		digits.setOnTouchListener(onTouchListener);
	}
	
	/*
	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		int kc = event.getKeyCode();
		if (event.getAction() != KeyEvent.ACTION_UP) {
			// Only handle up events here.
			return super.dispatchKeyEvent(event);
		}

		if (kc == KeyEvent.KEYCODE_CALL) {
			doCall();
			return true;
		} else if (kc == KeyEvent.KEYCODE_DEL) {
			removeClick();
			return true;
		} else if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) {
			char c = event.getNumber();
			String s = Character.toString(c);
			addClick(s, s);
			return true;
		} else if (kc >= KeyEvent.KEYCODE_A && kc <= KeyEvent.KEYCODE_Z) {
			char c = (char) event.getUnicodeChar();
			char num = mapToPhone(c);
			if (LOG) Log.d(TAG, "saw press [" + c + "] -> [" + num + "]");
			addClick(Character.toString(num),
					Character.toString(Character.toUpperCase(c)));
			return true;
		} else {
			return super.dispatchKeyEvent(event);
		}
	}
	*/
	
	private void createGlob() {
		char[] currInput = digitsView.getText().toString().toCharArray();
		curFilter.setLength(0);
		
		for (char c : currInput) {
			curFilter.append(buttonToGlobPiece(c));
		}
	}

	private void updateFilter(boolean add) {
		if (!add) {
			noMatches = false;
		}
		
		createGlob();
		
		if (noMatches) {
			return;
		} else {
			recalculate();
		}
	}

	private void removeAll() {
		curFilter.setLength(0);
		digitsView.getText().clear();
		noMatches = false;
		recalculate();
	}

	private void doCall() {
		Intent i = new Intent(Intent.ACTION_CALL);
		// if it was a long press do something else?
		i.setData(Uri.parse("tel://" + digitsView.getText().toString()));
		startActivity(i);
	}

	private void recalculate() {
		Cursor cur;		
		String s = curFilter.toString();
		
		if (s.indexOf("#") != -1) {
			s = s.replace('#', ' ');
		}
		
		if (s.indexOf('-') != -1) {
			s = s.replaceAll("-", "");
		}
		
		cur = contactAccessor.recalculate(s, matchAnywhere);

		startManagingCursor(cur);
		if (cur.getCount() == 0) {
			noMatches = true;
		}
		myAdapter.changeCursor(cur);
		myContactList.invalidate(); // the new filter requires we redraw
	}
	
	public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
	      // TODO Auto-generated method stub
	}
	
	public void onScrollStateChanged(AbsListView view, int scrollState) {
		// TODO Auto-generated method stub
		if(scrollState == OnScrollListener.SCROLL_STATE_FLING) {
			if (hideDialpadOnScroll) {
				toggleDialpad(false);
			}
		}
	}
	
	public void onClick(View view) {
		switch(view.getId()) {
		case R.id.button0: {
			playTone(ToneGenerator.TONE_DTMF_0);
            keyPressed(KeyEvent.KEYCODE_0);
            updateFilter(true);
			break;
		}
		case R.id.button1: {
			playTone(ToneGenerator.TONE_DTMF_1);
            keyPressed(KeyEvent.KEYCODE_1);
            updateFilter(true);
			break;
		}
		case R.id.button2: {
			playTone(ToneGenerator.TONE_DTMF_2);
            keyPressed(KeyEvent.KEYCODE_2);
            updateFilter(true);
			break;
		}
		case R.id.button3: {
			playTone(ToneGenerator.TONE_DTMF_3);
            keyPressed(KeyEvent.KEYCODE_3);
            updateFilter(true);
			break;
		}
		case R.id.button4: {
			playTone(ToneGenerator.TONE_DTMF_4);
            keyPressed(KeyEvent.KEYCODE_4);
            updateFilter(true);
			break;
		}
		case R.id.button5: {
			playTone(ToneGenerator.TONE_DTMF_5);
            keyPressed(KeyEvent.KEYCODE_5);
            updateFilter(true);
			break;
		}
		case R.id.button6: {
			playTone(ToneGenerator.TONE_DTMF_6);
            keyPressed(KeyEvent.KEYCODE_6);
            updateFilter(true);
			break;
		}
		case R.id.button7: {
			playTone(ToneGenerator.TONE_DTMF_7);
            keyPressed(KeyEvent.KEYCODE_7);
            updateFilter(true);
			break;
		}
		case R.id.button8: {
			playTone(ToneGenerator.TONE_DTMF_8);
            keyPressed(KeyEvent.KEYCODE_8);
            updateFilter(true);
			break;
		}
		case R.id.button9: {
			playTone(ToneGenerator.TONE_DTMF_9);
            keyPressed(KeyEvent.KEYCODE_9);
            updateFilter(true);
			break;
		}
		case R.id.buttonpound: {
			playTone(ToneGenerator.TONE_DTMF_P);
            keyPressed(KeyEvent.KEYCODE_POUND);
            updateFilter(true);
			break;
		}
		case R.id.buttonstar: {
			playTone(ToneGenerator.TONE_DTMF_S);
            keyPressed(KeyEvent.KEYCODE_STAR);
            updateFilter(true);
			break;
		}
		case R.id.deleteButton: {
			keyPressed(KeyEvent.KEYCODE_DEL);
			updateFilter(false);
			break;
		}
		case R.id.dialButton: {
			doCall();
			break;
		}
		case R.id.digitsText: {
			digitsView.setCursorVisible(false);
            if (digitsView.length() != 0) {
            	digitsView.setCursorVisible(true);
            }
            break;
		}
		
		case R.id.call_button: {
            String number = (String) view.getTag();
            if (!TextUtils.isEmpty(number)) {
                Uri telUri = Uri.fromParts("tel", number, null);
                startActivity(new Intent(Intent.ACTION_CALL, telUri));
            }
            return;
		}
		case R.id.name: {
			Uri lookupUri = (Uri)view.getTag();
			startContactActivity(lookupUri);
		}
		}
		toggleDrawable();
	}
	
	public boolean onLongClick(View view) {
		boolean result = false;
		switch (view.getId()) {
		case R.id.button0: {
			keyPressed(KeyEvent.KEYCODE_PLUS);
			result = true;
			updateFilter(true);
			break;
		}
		case R.id.button1: {
			if (digitsView.length() == 0) {
				if (hasVoicemail()) {
					Intent i = new Intent(Intent.ACTION_CALL);
					i.setData(Uri.parse("voicemail:"));
					startActivity(i);
					result = true;
					ImageButton digitOne = (ImageButton)findViewById(R.id.button1);
					digitOne.setPressed(false);
				}
			}
			break;
		}
		case R.id.deleteButton: {
			removeAll();
			deleteButton.setPressed(false);
			result = true;
			break;
		}
		}
		toggleDrawable();
		return result;
	}
    
    void playTone(int tone) {
    	if (!mDTMFToneEnabled) {
    		return;
    	}
    	
    	AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    	int ringerMode = audioManager.getRingerMode();
    	if ((ringerMode == AudioManager.RINGER_MODE_SILENT) || (ringerMode == AudioManager.RINGER_MODE_VIBRATE)) {
    		return;
    	}
    	
    	synchronized(mToneGeneratorLock) {
    		if (mToneGenerator == null) {
    			Log.w(TAG, "playTone: mToneGenerator == null, tone: "+tone);
    			return;
    		}
       		mToneGenerator.startTone(tone, TONE_LENGTH_MS);
    	}
    }
    
    private void keyPressed(int keyCode) {
        vibrate();
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        digitsView.onKeyDown(keyCode, event);
    }
    
    private synchronized void vibrate() {
        if (!prefVibrateOn) {
            return;
        }
        if (mVibrator == null) {
            mVibrator = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
        }
        mVibrator.vibrate(mVibratePattern, VIBRATE_NO_REPEAT);
    }
    
    private void initVibrationPattern() {
        int[] pattern = null;
        pattern = getResources().getIntArray(R.array.config_virtualKeyVibePattern);
        
        if (null == pattern) {
        	Log.e(TAG, "Vibrate pattern is null.");
        	prefVibrateOn = false;
        }

        if (!prefVibrateOn) {
            return;
        }

        // int[] to long[] conversion.
        mVibratePattern = new long[pattern.length];
        for (int i = 0; i < pattern.length; i++) {
            mVibratePattern[i] = pattern[i];
        }
    }
    
	// Listeners for the list items.
	private void startContactActivity(Uri lookupUri) {
		Uri contactUri = contactAccessor.getContactSplit().getContactUri(lookupUri);
		Intent i = new Intent(Intent.ACTION_VIEW);
		i.setData(contactUri);
		startActivity(i);
	}	
	
	public void onItemClick(AdapterView<?> parent, View view, int position,
			long rowid) {
		ContactListItemCache contact = (ContactListItemCache)view.getTag();
		startContactActivity(contact.lookupUri);
	}

	private class ContactListAdapter extends ResourceCursorAdapter {
		IContactSplit contactSplit;
		private Context mContext;
		
		public ContactListAdapter(Context context, Cursor cur, IContactSplit ics) {
			super(context, R.layout.recent_calls_list_item, cur, false);
			contactSplit = ics;
			mContext = context;
		}

		@Override
		public View newView(Context context, Cursor cursor, ViewGroup parent) {		
            final View view = super.newView(context, cursor, parent);

            final ContactListItemCache cache = new ContactListItemCache();
            cache.divider = view.findViewById(R.id.list_divider);
            cache.nameView = (TextView) view.findViewById(R.id.name);
            cache.callView = view.findViewById(R.id.call_view);
            cache.callButton = (ImageView) view.findViewById(R.id.call_button);
            if (cache.callButton != null) {
                cache.callButton.setOnClickListener(PhoneSpellDialer.this);
            }
            cache.labelView = (TextView) view.findViewById(R.id.label);
            cache.dataView = (TextView) view.findViewById(R.id.data);
            cache.photoView = (QuickContactBadge) view.findViewById(R.id.photo);
            cache.nonQuickContactPhotoView = (ImageView) view.findViewById(R.id.noQuickContactPhoto);
            
            view.setTag(cache);

            return view;
		}

		@Override
		public void bindView(View view, Context context, Cursor cursor) {

			final ContactListItemCache cache = (ContactListItemCache) view.getTag();
			final int DISPLAY_NAME_INDEX = 2;
			final int PHONE_NUMBER_INDEX = 3;
			final int PHONE_TYPE_INDEX = 4;
			final int PHONE_LABEL_INDEX = 5;
			final int PHOTO_ID_INDEX = 6;
			
            // Set the name
            final String name = cursor.getString(DISPLAY_NAME_INDEX);         	
            cache.nameView.setText(name, TextView.BufferType.SPANNABLE);
            highlightName((Spannable) cache.nameView.getText(), digitsView.getText().toString(), false);
            
            if (!cursor.isNull(PHONE_TYPE_INDEX)) {
                cache.labelView.setVisibility(View.VISIBLE);

                final int type = cursor.getInt(PHONE_TYPE_INDEX);
                final String label = cursor.getString(PHONE_LABEL_INDEX);
                cache.labelView.setText(Phone.getTypeLabel(context.getResources(), type, label));
            } else {
                // There is no label, hide the the view
            	cache.labelView.setVisibility(View.GONE);
            }
            
            final String number = cursor.getString(PHONE_NUMBER_INDEX);
            cache.dataView.setText(number, TextView.BufferType.SPANNABLE);
            highlightName((Spannable) cache.dataView.getText(), digitsView.getText().toString(), true);
            
            cache.callButton.setTag(number);
            Uri lookupUri = contactSplit.getLookupUri(cursor);
            cache.lookupUri = lookupUri;
            
            if (showContactPictures) {
            cache.photoView.assignContactUri(lookupUri);
            cache.photoView.setVisibility(View.VISIBLE);
            cache.nonQuickContactPhotoView.setVisibility(View.INVISIBLE);

            long photoId = -1;
            
            if (!cursor.isNull(PHOTO_ID_INDEX)) {
                photoId = cursor.getLong(PHOTO_ID_INDEX);
            }
            
            //Reference: http://thinkandroid.wordpress.com/2009/12/30/handling-contact-photos-all-api-levels/
            Bitmap photo = null;            
            if (photoId != -1) {
            	photo = loadContactPhoto(mContext, photoId, null);
            }
 
            if (photo != null) {
            	cache.photoView.setImageBitmap(photo);
            }
            else {
            	cache.photoView.setImageResource(R.drawable.ic_contact_list_picture);
            }
            }
            else {
            	cache.photoView.setVisibility(View.GONE);
            	cache.nonQuickContactPhotoView.setVisibility(View.GONE);
            }

		}

		@Override
		public String convertToString(Cursor cursor) {
			return cursor.getString(2);
		}
	}
	
    public static Bitmap loadContactPhoto(Context context, long photoId,
            BitmapFactory.Options options) {
        Cursor photoCursor = null;
        Bitmap photoBm = null;

        try {
            photoCursor = context.getContentResolver().query(
                    ContentUris.withAppendedId(Data.CONTENT_URI, photoId),
                    new String[] { Photo.PHOTO },
                    null, null, null);

            if (photoCursor.moveToFirst() && !photoCursor.isNull(0)) {
                byte[] photoData = photoCursor.getBlob(0);
                photoBm = BitmapFactory.decodeByteArray(photoData, 0,
                        photoData.length, options);
            }
        } finally {
            if (photoCursor != null) {
                photoCursor.close();
            }
        }

        return photoBm;
    }

	private static char mapToPhone(char alpha) {
		if (Character.isDigit(alpha) || alpha == '+')
			return alpha;
		
		if (alpha == ' ')
			return '#';
		
		char c = Character.toLowerCase(alpha);
		if (c < 'a' || c > 'z')
			return 0;
		if (c <= 'o') {
			int x = (c - 'a') / 3;
			return (char) ('2' + x);
		} else if (c >= 'p' && c <= 's') {
			return '7';
		} else if (c >= 't' && c <= 'v') {
			return '8';
		} else {
			return '9';
		}
	}
	
	/**
	 * Return the next match of pattern starting at 'offset', or -1 if there's
	 * no next match.
	 */
	
	private static int[] nextMatch(Spannable name, String pattern, boolean isNumber) {
		ArrayList<Integer> offsets = new ArrayList<Integer>();		
		String n = name.toString();		
		int k = 0;
		boolean match = true;
		
		if (isNumber) {
			pattern = pattern.replaceAll("-", "");
		}
		
		int[] result = new int[2];
		result[0] = -1; //start index of pattern matching
		result[1] = pattern.length(); //end index of pattern matching
		
		offsets.add(k);
		while (true) {
			k = n.indexOf(" ", k+1);
			if (k != -1)
				offsets.add(k+1);
			else
				break;				
		}
		
		for (int j = 0; j < offsets.size(); j++) {
			
			if (mapToPhone(name.charAt(offsets.get(j))) == pattern.charAt(0)) {
				match = true;
				result[0] = offsets.get(j);
			}
			
			if (match) {
				int currPos = offsets.get(j);
				for (int i = 0; i < pattern.length(); i++) {
					try {
						if (mapToPhone(name.charAt(currPos)) != pattern.charAt(i)) {
							if (name.charAt(currPos) == '-') {
								result[1]++;
								i--;
							}
							else {							
								match = false;
								result[0] = -1;
								result[1] = pattern.length();
								break;
							}
						}					
					}
					catch (StringIndexOutOfBoundsException e) {
						match = false;
						result[0] = -1;
						result[1] = pattern.length();
					}
					currPos++;
				}
			}
			
			if (match) {
				break;
			}			
		}
		
		//Wysie: If all the above fail, check if it's matchAnywhere iff it's a number
		if (!match && matchAnywhere && isNumber) {
			int length = name.length() - pattern.length() + 1;
			for (int j = 0; j < length; j++) {
				if (name.charAt(j) == pattern.charAt(0)) {
					match = true;
					int currPos = j;
					for (int i = 0; i < pattern.length(); i++) {
						if (name.charAt(currPos) != pattern.charAt(i)) {
							if (name.charAt(currPos) == '-') {
								result[1]++;
								i--;
							}
							else {
								match = false;
								result[0] = -1;
								result[1] = pattern.length();
								break;
							}
						}
						currPos++;
					}
				}
				
				if (match) {
					result[0] = j;
					break;
				}
			}
		}
				
		return result;
	}

	private static void applyHighlight(Spannable name, int start, int len) {
		if (len == 0) return;
		Log.d("SPANNABLE NAME", ""+name);
		
		if (matchedItalics) {
			name.setSpan(ITALIC_STYLE, start, start + len,
			 Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		
		if (matchedBold) {
			name.setSpan(BOLD_STYLE, start, start + len,
					 Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		
		if (matchedDigits) {
			name.setSpan(matchedDigitsColor, start, start + len,
					Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		
		if (matchedHighlight) {
			name.setSpan(matchedHighlightColor, start, start + len,
					Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
	}

	private static void highlightName(Spannable name, String pattern, boolean isNumber) {
		if (pattern.length() == 0)
			return;
		
		int[] match = nextMatch(name, pattern, isNumber);
		if (match[0] != -1) {
			applyHighlight(name, match[0], match[1]);
		}
	}
	
	private void toggleDrawable() {
        final boolean notEmpty = digitsView.length() != 0;
        if (notEmpty) {
        	digitsView.setBackgroundDrawable(mDigitsBackground);
        	dialButton.setEnabled(true);
        	deleteButton.setEnabled(true);
        } else {
        	digitsView.setCursorVisible(false);
            digitsView.setBackgroundDrawable(mDigitsEmptyBackground);
            dialButton.setEnabled(false);
        	deleteButton.setEnabled(false);
        }
	}	
	
    private final OnTouchListener onTouchListener = new OnTouchListener() {
    	GestureDetector detect = new GestureDetector(new ToggleDialPadListener());
    	{
    		detect.setIsLongpressEnabled(false);
    	}
    	public boolean onTouch(View v, MotionEvent event) {
    		return detect.onTouchEvent(event);
    	}
    };
    
    private class ToggleDialPadListener extends SimpleOnGestureListener {
    	private static final float MIN_VELOCITY_DIP = -100.0f;
    	private static final float MIN_VELOCITY_RISE = 100.0f;
    	
    	@Override
    	public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
    		if (velocityY < MIN_VELOCITY_DIP) {
    			toggleDialpad(true);
    		}
    		else if (velocityY > MIN_VELOCITY_RISE) {
    			toggleDialpad(false);
    		}
    		return super.onFling(e1, e2, velocityX, velocityY);
    	}
    }
    
    private void toggleDialpad(boolean showDialPad) {
    	View dialPad = findViewById(R.id.keypad);
    	if (showDialPad) {    		
    		dialPad.setVisibility(View.VISIBLE);
    	}
    	else {
    		dialPad.setVisibility(View.GONE);    	
    	}
    }

	private static String buttonToGlobPiece(char c) {
		switch (c) {
		case '2':
			return "[2ABC]";
		case '3':
			return "[3DEF]";
		case '4':
			return "[4GHI]";
		case '5':
			return "[5JKL]";
		case '6':
			return "[6MNO]";
		case '7':
			return "[7PQRS]";
		case '8':
			return "[8TUV]";
		case '9':
			return "[9WXYZ]";
		case '*':
			return "?";
		default:
			return String.valueOf(c);
		}
	}
	
    //Wysie: Method to set digits colour
    private void setDigitsColor(SharedPreferences ePrefs) {
        int colorPressed = -16777216;
        int colorFocused = -1;
        int colorUnselected = -1;
        
        if (ePrefs.getBoolean("dial_digit_use_custom_color", false)) {
            try {
                colorPressed = Color.parseColor(ePrefs.getString("pressed_digit_color_custom", "-16777216"));
                colorFocused = Color.parseColor(ePrefs.getString("focused_digit_color_custom", "-1"));                
                colorUnselected = Color.parseColor(ePrefs.getString("unselected_digit_color_custom", "-1"));
            }
            catch (IllegalArgumentException e) {
                //Do nothing
            }            
        }
        else {
            colorPressed = Integer.parseInt(ePrefs.getString("pressed_digit_color", "-16777216"));
            colorFocused = Integer.parseInt(ePrefs.getString("focused_digit_color", "-1"));
            colorUnselected = Integer.parseInt(ePrefs.getString("unselected_digit_color", "-1"));
        }
    
        digitsView.setTextColor(new ColorStateList(
                     new int[][] {
                             new int[] { android.R.attr.state_pressed },
                             new int[] { android.R.attr.state_focused },
                             new int[0]},
                     
                             new int[] { colorPressed, colorFocused, colorUnselected }
                     ));
        digitsView.setCursorVisible(false);
    }
    
    //Wysie: Check for voicemail number    
    private boolean hasVoicemail() {
    	boolean hasVoicemail = false; 
    	TelephonyManager mgr = (TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE);
    	
    	try {
    		String num = mgr.getVoiceMailNumber();
    		if (!(num == null || num.equals(""))) 
    			hasVoicemail = true;
    	} catch (SecurityException se) {
    		// Possibly no READ_PHONE_STATE privilege.
    	} catch (NullPointerException e) {
    		//
    	}
    	
    	return hasVoicemail;
    }
    
    final static class ContactListItemCache {
        public View divider;
        public TextView nameView;
        public View callView;
        public ImageView callButton;
        public TextView labelView;
        public TextView dataView;
        public Uri lookupUri;
        
        public QuickContactBadge photoView;
        public ImageView nonQuickContactPhotoView;
    }
}