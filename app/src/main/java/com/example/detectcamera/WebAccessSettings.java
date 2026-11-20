package com.example.detectcamera;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.security.SecureRandom;

/** Credenciales persistentes para que el WebServer nunca quede publicado sin autenticación. */
public final class WebAccessSettings {
    private static final String PREFS = "prux_web_access";
    private static final String KEY_USER = "username";
    private static final String KEY_PASS = "password";
    private static final String DEFAULT_USER = "prux";

    private WebAccessSettings() { }

    private static synchronized String[] getOrCreate(Context context) {
        SharedPreferences prefs;
        try {
            MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            prefs = EncryptedSharedPreferences.create(
                    context.getApplicationContext(), PREFS, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "No se pudo abrir el almacenamiento cifrado de las credenciales web.", error);
        }
        String user = prefs.getString(KEY_USER, null);
        String pass = prefs.getString(KEY_PASS, null);
        if (user == null || user.trim().isEmpty() || pass == null || pass.length() < 32) {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            user = DEFAULT_USER;
            pass = Base64.encodeToString(secret,
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            prefs.edit().putString(KEY_USER, user).putString(KEY_PASS, pass).apply();
        }
        return new String[]{user, pass};
    }

    public static void apply(Context context, WebServer server) {
        String[] credentials = getOrCreate(context);
        server.setCredenciales(credentials[0], credentials[1]);
    }

    public static String getDisplayText(Context context) {
        String[] credentials = getOrCreate(context);
        return "Acceso local protegido (HTTP Basic)\nUsuario: " + credentials[0]
                + "\nContraseña: " + credentials[1]
                + "\nSe usa para entrar directamente por IP:8080 en la misma red Wi-Fi. "
                + "El acceso remoto por PRUX Central se autoriza con tu sesión de administrador.";
    }
}
