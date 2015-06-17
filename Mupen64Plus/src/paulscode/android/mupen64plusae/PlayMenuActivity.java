/**
 * Mupen64PlusAE, an N64 emulator for the Android platform
 * 
 * Copyright (C) 2013 Paul Lamb
 * 
 * This file is part of Mupen64PlusAE.
 * 
 * Mupen64PlusAE is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * Mupen64PlusAE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Mupen64PlusAE. If
 * not, see <http://www.gnu.org/licenses/>.
 * 
 * Authors: Paul Lamb
 */
package paulscode.android.mupen64plusae;

import java.io.File;

import paulscode.android.mupen64plusae.persistent.AppData;
import paulscode.android.mupen64plusae.persistent.CheatPreference;
import paulscode.android.mupen64plusae.persistent.ConfigFile;
import paulscode.android.mupen64plusae.persistent.ConfigFile.ConfigSection;
import paulscode.android.mupen64plusae.persistent.PlayerMapPreference;
import paulscode.android.mupen64plusae.persistent.UserPrefs;
import paulscode.android.mupen64plusae.util.Notifier;
import paulscode.android.mupen64plusae.util.PrefUtil;
import paulscode.android.mupen64plusae.util.Prompt;
import paulscode.android.mupen64plusae.util.Prompt.PromptConfirmListener;
import paulscode.android.mupen64plusae.util.SafeMethods;
import paulscode.android.mupen64plusae.util.TaskHandler;
import paulscode.android.mupen64plusae.util.TaskHandler.Task;
import paulscode.android.mupen64plusae.util.Utility;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;

import com.bda.controller.Controller;

public class PlayMenuActivity extends PreferenceActivity implements OnPreferenceClickListener,
        OnSharedPreferenceChangeListener
{
    // These constants must match the keys used in res/xml/preferences_play.xml
    private static final String SCREEN_PLAY = "screenPlay";
    private static final String ACTION_RESUME = "actionResume";
    private static final String ACTION_RESTART = "actionRestart";
    private static final String PLAYER_MAP = "playerMap";
    private static final String PLAY_SHOW_CHEATS = "playShowCheats";
    private static final String CATEGORY_CHEATS = "categoryCheats";
    
    // App data and user preferences
    private AppData mAppData = null;
    private UserPrefs mUserPrefs = null;
    
    // Handle to the thread populating the cheat options
    private Thread crcThread = null;
    
    // MOGA controller interface
    private Controller mMogaController = Controller.getInstance( this );
    
    @SuppressWarnings( "deprecation" )
    @Override
    protected void onCreate( Bundle savedInstanceState )
    {
        super.onCreate( savedInstanceState );
        
        // Initialize MOGA controller API
        mMogaController.init();
        
        // Get app data and user preferences
        mAppData = new AppData( this );
        mUserPrefs = new UserPrefs( this );
        mUserPrefs.enforceLocale( this );
        
        // Load user preference menu structure from XML and update view
        addPreferencesFromResource( R.xml.preferences_play );
        
        // Handle certain menu items that require extra processing or aren't actually preferences
        PrefUtil.setOnPreferenceClickListener( this, ACTION_RESUME, this );
        PrefUtil.setOnPreferenceClickListener( this, ACTION_RESTART, this );
        
        // Hide the multi-player menu if not needed
        if( !mUserPrefs.playerMap.isEnabled() )
        {
            PrefUtil.removePreference( this, SCREEN_PLAY, PLAYER_MAP );
        }
        else
        {
            PlayerMapPreference preference = (PlayerMapPreference) findPreference( PLAYER_MAP );
            preference.setMogaController( mMogaController );
        }
        
        // Hide or populate the cheats category depending on user preference
        if( mUserPrefs.isCheatOptionsShown )
        {
            // Populate cheats category with menu items
            if( mUserPrefs.selectedGame.equals( mAppData.getLastRom() ) )
            {
                // Use the cached CRC and add the cheats menu items
                build( mAppData.getLastCrc() );
            }
            else
            {
                // Recompute the CRC in a separate thread, then add the cheats menu items
                rebuild( mUserPrefs.selectedGame );
            }
        }
        else
        {
            // Hide the cheats category
            PrefUtil.removePreference( this, SCREEN_PLAY, CATEGORY_CHEATS );
        }
    }
    
    @Override
    protected void onResume()
    {
        super.onResume();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences( this );
        sharedPreferences.registerOnSharedPreferenceChangeListener( this );
        mMogaController.onResume();
    }
    
    @Override
    protected void onPause()
    {
        super.onPause();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences( this );
        sharedPreferences.unregisterOnSharedPreferenceChangeListener( this );
        mMogaController.onPause();
    }
    
    @Override
    public void onDestroy()
    {
        super.onDestroy();
        mMogaController.exit();
    }
    
    @Override
    public void finish()
    {
        // Disable transition animation to behave like any other screen in the menu hierarchy
        super.finish();
        overridePendingTransition( 0, 0 );
    }
    
    @Override
    public void onSharedPreferenceChanged( SharedPreferences sharedPreferences, String key )
    {
        if( key.equals( PLAY_SHOW_CHEATS ) )
        {
            // Rebuild the menu; the easiest way is to simply restart the activity
            startActivity( getIntent() );
            finish();
        }
        mUserPrefs = new UserPrefs( this );
    }
    
    @Override
    public boolean onPreferenceClick( Preference preference )
    {
        String key = preference.getKey();
        if( key.equals( ACTION_RESUME ) )
        {
            launchGame( false );
            return true;
        }
        else if( key.equals( ACTION_RESTART ) )
        {
            CharSequence title = getText( R.string.confirm_title );
            CharSequence message = getText( R.string.confirmResetGame_message );
            Prompt.promptConfirm( this, title, message, new PromptConfirmListener()
            {
                @Override
                public void onConfirm()
                {
                    launchGame( true );
                }
            } );
            return true;
        }
        return false;
    }
    
    private void rebuild( final String rom )
    {
        Log.v( "PlayMenuActivity", "rebuilding for ROM = " + rom );
        Notifier.showToast( PlayMenuActivity.this, R.string.toast_rebuildingCheats );
        
        // Place to unzip the ROM if necessary
        final String tmpFolderName = mAppData.tempDir;
        
        // Define the task to be done on a separate thread
        Task task = new Task()
        {
            private String crc;
            
            @Override
            public void run()
            {
                crc = Utility.getHeaderCRC( rom, tmpFolderName );
            }
            
            @Override
            public void onComplete()
            {
                mAppData.putLastRom( rom );
                mAppData.putLastCrc( crc );
                build( crc );
            }
        };
        
        // Run the task on a separate thread
        crcThread = TaskHandler.run( this, "ReadGameHeader", task );
    }
    
    @SuppressWarnings( "deprecation" )
    private void build( final String crc )
    {
        Log.v( "PlayMenuActivity", "building from CRC = " + crc );
        
        if( crc == null )
            return;
        
        // Get the appropriate section of the config file, using CRC as the key
        ConfigFile mupen64plus_cht = new ConfigFile( mAppData.mupen64plus_cht );
        ConfigSection configSection = mupen64plus_cht.match( "^" + crc.replace( ' ', '.' ) + ".*" );
        
        if( configSection == null )
        {
            Log.w( "PlayMenuActivity", "No cheat section found for '" + crc + "'" );
            return;
        }
        
        // Set the title of the menu to the game name, if available
        // String ROM_name = configSection.get( "Name" );
        // if( !TextUtils.isEmpty( ROM_name ) )
        // setTitle( ROM_name );
        
        // Layout the menu, populating it with appropriate cheat options
        PreferenceCategory cheatsCategory = (PreferenceCategory) findPreference( CATEGORY_CHEATS );
        String cheat = " ";
        for( int i = 0; !TextUtils.isEmpty( cheat ); i++ )
        {
            cheat = configSection.get( "Cheat" + i );
            if( !TextUtils.isEmpty( cheat ) )
            {
                // Get the short title of the cheat (shown in the menu)
                int x = cheat.indexOf( "," );
                String title;
                if( x < 3 || x >= cheat.length() )
                {
                    // Title not available, just use a default string for the menu
                    title = getString( R.string.cheats_defaultName, i );
                }
                else
                {
                    // Title available, remove the leading/trailing quotation marks
                    title = cheat.substring( 1, x - 1 );
                }
                
                // Get the descriptive note for this cheat (shown on long-click)
                final String notes = configSection.get( "Cheat" + i + "_N" );
                
                // Get the options for this cheat
                final String val_O = configSection.get( "Cheat" + i + "_O" );
                String[] optionStrings = null;
                
                if( !TextUtils.isEmpty( val_O ) )
                {
                    // This is a multi-choice cheat
                    // Parse the comma-delimited string to get the map elements
                    String[] uOpts = val_O.split( "," );
                    optionStrings = new String[uOpts.length];
                    
                    // Each element is a key-value pair
                    for( int z = 0; z < uOpts.length; z++ )
                    {
                        // The first non-leading space character is the pair delimiter
                        optionStrings[z] = uOpts[z].trim();
                        int c = optionStrings[z].indexOf( " " );
                        if( c > -1 && c < optionStrings[z].length() - 1 )
                            optionStrings[z] = optionStrings[z].substring( c + 1 );
                        else
                            optionStrings[z] = getString( R.string.cheats_longPress );
                    }
                }
                
                // Create the menu item associated with this cheat
                CheatPreference pref = new CheatPreference( this, title, notes, optionStrings );
                pref.setKey( crc + " Cheat" + i );
                
                // Add the preference menu item to the cheats category
                cheatsCategory.addPreference( pref );
            }
        }
    }
    
    private void launchGame( boolean isRestarting )
    {
        // Popup the multi-player dialog and abort if any players don't have a controller assigned
        if( mUserPrefs.playerMap.isEnabled() && mUserPrefs.getPlayerMapReminder() )
        {
            mUserPrefs.playerMap.removeUnavailableMappings();
            boolean needs1 = mUserPrefs.isInputEnabled1 && !mUserPrefs.playerMap.isMapped( 1 );
            boolean needs2 = mUserPrefs.isInputEnabled2 && !mUserPrefs.playerMap.isMapped( 2 );
            boolean needs3 = mUserPrefs.isInputEnabled3 && !mUserPrefs.playerMap.isMapped( 3 );
            boolean needs4 = mUserPrefs.isInputEnabled4 && !mUserPrefs.playerMap.isMapped( 4 );
            
            if( needs1 || needs2 || needs3 || needs4 )
            {
                @SuppressWarnings( "deprecation" )
                PlayerMapPreference pref = (PlayerMapPreference) findPreference( "playerMap" );
                pref.show();
                return;
            }
        }
        
        // Wait for the CRC calculation thread to finish
        SafeMethods.join( crcThread, 0 );
        
        // Make sure that the storage is accessible
        if( !mAppData.isSdCardAccessible() )
        {
            Log.e( "CheatMenuHandler", "SD Card not accessible in method onPreferenceClick" );
            Notifier.showToast( this, R.string.toast_sdInaccessible );
            return;
        }
        
        // Make sure that the game save subdirectories exist so that we can write to them
        UserPrefs userPrefs = new UserPrefs( this );
        new File( userPrefs.manualSaveDir ).mkdirs();
        new File( userPrefs.slotSaveDir ).mkdirs();
        new File( userPrefs.autoSaveDir ).mkdirs();
        
        // Notify user that the game activity is starting
        Notifier.showToast( this, R.string.toast_launchingEmulator );
        
        // Set the startup mode for the core
        CoreInterface.setStartupMode( getCheatArgs(), isRestarting );
        
        // Launch the appropriate game activity
        Intent intent = userPrefs.isTouchpadEnabled ? new Intent( this,
                GameActivityXperiaPlay.class ) : new Intent( this, GameActivity.class );
        
        startActivity( intent );
    }
    
    @SuppressWarnings( "deprecation" )
    private String getCheatArgs()
    {
        String cheatArgs = null;
        
        PreferenceCategory cheatsCategory = (PreferenceCategory) findPreference( CATEGORY_CHEATS );
        if( cheatsCategory != null )
        {
            for( int i = 0; i < cheatsCategory.getPreferenceCount(); i++ )
            {
                CheatPreference pref = (CheatPreference) cheatsCategory.getPreference( i );
                if( pref.isCheatEnabled() )
                {
                    if( cheatArgs == null )
                        cheatArgs = ""; // First time through
                    else
                        cheatArgs += ",";
                    
                    cheatArgs += pref.getCheatCodeString( i );
                }
            }
        }
        
        return cheatArgs;
    }
}
