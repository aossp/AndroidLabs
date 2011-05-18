/**
 * Copyright 2011 Security Compass
 */

package com.securitycompass.labs.falsesecuremobile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;

import org.json.JSONException;

import android.accounts.AuthenticatorException;
import android.app.Application;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.os.Environment;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

/**
 * Stores session keys and handles moving the application between locked and unlocked states.
 * Instantiated when the application loads.
 * @author Ewan Sinclair
 */
public class BankingApplication extends Application {

    private String sessionKey;
    private String sessionCreateDate;
    private boolean locked;

    private int foregroundedActivities;
    private Handler timingHandler;

    // How many hashing iterations to perform
    private static final int HASH_ITERATIONS = 1000;

    /** Where we'll store statements */
    private String mStatementDir;

    /* These variables are used for anchoring preference keys */
    /** The name of the shared preferences file for prefs not accessible via the preferences screen */
    public static final String SHARED_PREFS = "preferences";
    /** Whether the application is running for the first time */
    public static final String PREF_FIRST_RUN = "firstrun";
    /** A hash of the local password */
    public static final String PREF_LOCALPASS_HASH = "localpasshash";
    /** The salt used when hashing the local password */
    public static final String PREF_LOCALPASS_SALT = "localpasssalt";
    /** The username to present to the banking service */
    public static final String PREF_REST_USER = "serveruser";
    /** The password to present to the banking service */
    public static final String PREF_REST_PASSWORD = "serverpass";

    /** A tag to identify the class if it logs anything */
    public static final String TAG = "BankingApplication";

    /** Setup for when the application initialises. */
    @Override
    public void onCreate() {
        super.onCreate();
        timingHandler = new Handler();
        foregroundedActivities = 0;
        locked = true;
        mStatementDir = Environment.getExternalStorageDirectory().toString()
                + "/falsesecuremobile/";
    }

    /**
     * Returns a string representation of the server we will be making our requests on.
     * @return A string representation of the server address.
     */
    public String getRestServer() {
        return PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getString(
                "bankserviceaddress", "10.0.2.2");
    }

    /**
     * Returns a string representation of the port we will be making our HTTP requests on.
     * @return A string representation of the port set for HTTP communication.
     */
    public String getPort() {
        if (isHttpsEnabled()) {
            return PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                    .getString("httpsport", "8443");
        } else {
            return PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                    .getString("httpport", "8080");
        }
    }

    /**
     * Returns the directory where statements are kept, as a String.
     * @return The directory where statements are kept, as a String.
     */
    public String getStatementDir() {
        return getFilesDir().toString();
    }

    /**
     * Returns whether the HTTPS setting is enabled, as a boolean.
     * @return whether the HTTPS setting is enabled, as a boolean.
     */
    public boolean isHttpsEnabled() {
        return PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).getBoolean(
                "httpsenabled", false);
    }

    /**
     * Sets the local password, accomplished by storing a hashcode.
     * @param password The plain String version of the password to set.
     * @throws NoSuchAlgorithmException if the hashing algorithm is unavailable
     * @throws UnsupportedEncodingException if Base64 encoding is not available
     */
    public void setLocalPassword(String password) throws NoSuchAlgorithmException,
            UnsupportedEncodingException {

        // First we generate a random salt
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        byte[] saltByteArray = new byte[32];
        random.nextBytes(saltByteArray);

        // Perform the hash, getting a Base64 encoded String
        String hashString = hash(password, saltByteArray);

        // Base64 encode the salt, store our strings
        byte[] b64Salt = Base64.encode(saltByteArray, Base64.DEFAULT);
        String saltString = new String(b64Salt);

        Editor e = getSharedPrefs().edit();
        e.putString(PREF_LOCALPASS_HASH, hashString);
        e.putString(PREF_LOCALPASS_SALT, saltString);
        e.commit();
    }

    /**
     * Checks to see if the given password hashes to the same value as the stored one.
     * @param enteredPassword The password to check.
     * @return Whether the password matched.
     * @throws NoSuchAlgorithmException if the hashing algorithm is unavailable
     * @throws UnsupportedEncodingException if Base64 encoding is not available
     */
    public boolean checkPassword(String enteredPassword) throws NoSuchAlgorithmException,
            UnsupportedEncodingException {
        String saltString = getSharedPrefs().getString(PREF_LOCALPASS_SALT, "");
        byte[] saltBytes = Base64.decode(saltString, Base64.DEFAULT);
        String hashOfEnteredPassword = hash(enteredPassword, saltBytes);
        String hashOfStoredpassword = getSharedPrefs().getString(PREF_LOCALPASS_HASH, "");
        return hashOfEnteredPassword.equals(hashOfStoredpassword);
    }

    /**
     * Peforms a bunch of hash iterations on the given String and salt and returns the result.
     * @param password String password to hash.
     * @param salt The salt to hash with.
     * @return A base64 encoded string representing the hash.
     * @throws NoSuchAlgorithmException
     * @throws UnsupportedEncodingException
     */
    private String hash(String password, byte[] salt) throws NoSuchAlgorithmException,
            UnsupportedEncodingException {
        byte[] passwordBytes = (password).getBytes("UTF-8");

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.reset();
        md.update(salt);
        byte[] hashed = md.digest(passwordBytes);

        for (int count = 0; count < HASH_ITERATIONS; count++) {
            md.reset();
            md.update(salt);
            hashed = md.digest(hashed);
        }

        byte[] b64Hash = Base64.encode(hashed, Base64.DEFAULT);
        return new String(b64Hash);

    }

    /**
     * Returns the shared preferences for this app, with permission mode pre-set.
     * @return The shared preferences for this app, with permission mode pre-set.
     */
    public SharedPreferences getSharedPrefs() {
        return getSharedPreferences(SHARED_PREFS, MODE_WORLD_READABLE);
    }

    /**
     * Logs into the REST service, generating a new session key.
     * @param username Username to log in with.
     * @param password Password to log in with.
     * @return A status code representing any error that occurred.
     * @throws JSONException if the server returned invalid JSON
     * @throws IOException if there was a communication error with the server
     * @throws KeyManagementException if the server key couldn't be trusted
     * @throws HttpException if the HTTP/S request failed
     * @throws NoSuchAlgorithmException if the set SSL encryption algorithm is unavilable
     */
    public int performLogin(String username, String password) throws JSONException, IOException,
            KeyManagementException, HttpException, NoSuchAlgorithmException {
        RestClient restClient = new RestClient(this, isHttpsEnabled());
        int statusCode = restClient.performLogin(getRestServer(), getPort(), username, password);
        return statusCode;
    }

    /** Performs all operations necessary to secure the application. */
    public void lockApplication() {
        locked = true;
    }

    /**
     * Performs all operations necessary to make the application usable.
     * @param password The password to try unlocking with
     * @return Whether the operation succeeded
     * @throws UnsupportedEncodingException if Base64 encoding isn't available
     * @throws NoSuchAlgorithmException if the hashing algorithm couldn't be found
     * @throws JSONException if the server returned invalid JSON
     * @throws IOException if there was a communication error with the server
     * @throws KeyManagementException if the server key couldn't be trusted
     * @throws HttpException if the HTTP/S request failed
     */
    public int unlockApplication(String password) throws UnsupportedEncodingException,
            NoSuchAlgorithmException, IOException, JSONException, KeyManagementException,
            HttpException {
        if (checkPassword(password)) {
            String user = getRestUsername();
            String pass = getRestPassword();
            int statusCode = performLogin(user, pass);
            if (statusCode == RestClient.NULL_ERROR) {
                locked = false;
                return statusCode;
            }
        }
        return RestClient.NO_OP;
    }

    /**
     * Returns the application's state of lockdown.
     * @return The application's state of lockdown.
     */
    public boolean isLocked() {
        return locked;
    }

    /**
     * Returns the stored username for the REST service.
     * @return The stored username for the REST service.
     */
    public String getRestUsername() {
        return getSharedPrefs().getString(PREF_REST_USER, "");
    }

    /**
     * Returns the stored password for the REST service.
     * @return The stored password for the REST service.
     */
    public String getRestPassword() {
        return getSharedPrefs().getString(PREF_REST_PASSWORD, "");
    }

    /**
     * Sets the user's credentials for the banking service.
     * @param username The username to set.
     * @param password The password to set.
     */
    public void setServerCredentials(String username, String password) {
        Editor e = getSharedPrefs().edit();
        e.putString(PREF_REST_USER, username);
        e.putString(PREF_REST_PASSWORD, password);
        e.commit();
    }

    /**
     * Returns a list of all Accounts and their details.
     * @return A list of the accounts returned by the server, represented as Account objects.
     * @throws JSONException if the server returned invalid JSON
     * @throws IOException if the network connection failed
     * @throws AuthenticatorException if the server rejects the session key
     * @throws NoSuchAlgorithmException if the algorithm used to hash the password is not available
     * @throws KeyManagementException if the server's SSL certificate couldn't be trusted
     */
    public List<Account> getAccounts() throws JSONException, IOException, AuthenticatorException,
            NoSuchAlgorithmException, KeyManagementException {
        RestClient restClient = new RestClient(this, isHttpsEnabled());
        List<Account> result = null;
        try {
            result = restClient.getAccounts(getRestServer(), getPort());
        } catch (AuthenticatorException e) {
            lockApplication();
            throw e;
        }

        // Log the account details
        String logString = "Accounts:\n";
        for (Account a : result) {
            logString += a.toString() + "\n";
        }
        Log.i(TAG, logString);
        return result;
    }

    /**
     * Downloads a statement and displays it.
     * @throws IOException if network communication failed
     * @throws NoSuchAlgorithmException if the algorithm used to hash the password is unavilable
     * @throws KeyManagementException if the server's SSL certificate couldn't be trusted
     * @throws AuthenticatorException if the server rejected the session key
     */
    public void downloadStatement() throws IOException, NoSuchAlgorithmException,
            KeyManagementException, AuthenticatorException {
        RestClient restClient = new RestClient(this, isHttpsEnabled());

        String statementHtml = restClient.getStatement(getRestServer(), getPort());

        FileOutputStream outputFileStream = openFileOutput(Long
                .toString(System.currentTimeMillis())
                + ".html", MODE_PRIVATE);

        outputFileStream.write(statementHtml.getBytes());
        outputFileStream.flush();
        outputFileStream.close();
    }

    /**
     * Clears all statements from the download directory
     */
    public void clearStatements() {
        File downloadDir = getFilesDir();
        File[] directoryContents = downloadDir.listFiles();
        if (directoryContents != null) {
            for (File f : directoryContents) {
                f.delete();
            }
        }
    }

    /**
     * Transfers money between accounts
     * @param fromAccount The account to take funds from.
     * @param toAccount The account in which to deposit the funds.
     * @param amount The amount to transfer.
     * @return A status code representing the server's response
     * @throws IOException if network communication failed
     * @throws NoSuchAlgorithmException if the algorithm used to hash the password is unavilable
     * @throws KeyManagementException if the server's SSL certificate couldn't be trusted
     * @throws HttpException if the HTTP/S request failed
     */
    public int transferFunds(int fromAccount, int toAccount, double amount) throws IOException,
            NoSuchAlgorithmException, KeyManagementException, HttpException {
        RestClient restClient = new RestClient(this, isHttpsEnabled());
        int statusCode = restClient.transfer(getRestServer(), getPort(), fromAccount, toAccount,
                amount, sessionKey);
        return statusCode;
    }

    /**
     * Sets the details for the current authenticated session.
     * @param key The session key.
     * @param date A string representation of the date this key was issued.
     */
    public void setSession(String key, String date) {
        sessionKey = key;
        sessionCreateDate = date;
    }

    /**
     * Returns the current session key.
     * @return The current session key.
     */
    public String getSessionKey() {
        return sessionKey;
    }

    /**
     * Returns the creation date of the current session key.
     * @return A String representation of the creation date of the current session key.
     */
    public String getSessionCreateDate() {
        return sessionCreateDate;
    }

    /** Informs the application that an Activity is in the foreground */
    public void registerActivityForegrounded() {
        foregroundedActivities++;
    }

    /**
     * Informs the Application that an Activity has entered the background, and sets a check in 2
     * seconds to see if the entire application is now in the background
     */
    public void registerActivityBackgrounded() {
        foregroundedActivities--;
        timingHandler.removeCallbacks(checkBackgroundTask);
        timingHandler.postDelayed(checkBackgroundTask, 2000);
    }

    /**
     * Checks if all Activities are in the background (e.g. the entire app), and locks the app if
     * they are.
     */
    public void checkIfBackgrounded() {
        if (foregroundedActivities == 0) {
            lockApplication();
        }
    }

    /**
     * A simple helper class to perform a delayed check of whether the application is backgrounded
     */
    public Runnable checkBackgroundTask = new Runnable() {

        @Override
        public void run() {
            checkIfBackgrounded();
        }

    };

}