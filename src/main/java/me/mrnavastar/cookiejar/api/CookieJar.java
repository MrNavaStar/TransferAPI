package me.mrnavastar.cookiejar.api;

import com.esotericsoftware.kryo.kryo5.Kryo;
import com.esotericsoftware.kryo.kryo5.io.Input;
import com.esotericsoftware.kryo.kryo5.io.Output;
import me.mrnavastar.cookiejar.DebugCommands;
import me.mrnavastar.cookiejar.util.CookieSigner;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerCookieStore;
import net.minecraft.util.Identifier;

import javax.crypto.Mac;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class CookieJar implements DedicatedServerModInitializer {

    // Map of cooke ids, signing secrets, and hashing macs
    private static final ConcurrentHashMap<Class<?>, RegisteredCookie> registeredCookies = new ConcurrentHashMap<>();
    private static final Kryo kryo = new Kryo();

    static {
        kryo.setRegistrationRequired(false);
    }

    private record RegisteredCookie(Identifier identifier, byte[] secret, Mac mac) {}

    public static class CookieRegistrar {
        private final Class<?> cookieType;
        private byte[] secret = new byte[]{};
        private Mac mac = CookieSigner.DEFAULT_MAC;

        public CookieRegistrar(Class<?> cookieType) {
            this.cookieType = cookieType;
        }

        public CookieRegistrar setSecret(byte[] secret) {
            this.secret = secret;
            return this;
        }

        public CookieRegistrar setSecret(String secret) {
            return setSecret(secret.getBytes(StandardCharsets.UTF_8));
        }

        public CookieRegistrar setCustomMac(Mac mac) {
            this.mac = mac;
            return this;
        }

        public void finish() {
            Identifier id = new Identifier("cookiejar", UUID.nameUUIDFromBytes(cookieType.getName().getBytes(StandardCharsets.UTF_8)).toString());
            registeredCookies.put(cookieType, new RegisteredCookie(id, secret, mac));
        }
    }

    @Override
    public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> DebugCommands.init(dispatcher));
    }

    public static CookieRegistrar register(Class<?> cookieType) {
        return new CookieRegistrar(cookieType);
    }

    public static void unregister(Class<?> cookieType) {
        registeredCookies.remove(cookieType);
    }

    public static void setCookie(ServerCookieStore cookieStore, Object cookie) {
        RegisteredCookie registeredCookie = registeredCookies.get(cookie.getClass());
        if (registeredCookie == null) return;

        try (ByteArrayOutputStream out = new ByteArrayOutputStream(5120)) {
            kryo.writeObject(new Output(out), cookie);
            cookieStore.setCookie(registeredCookie.identifier, CookieSigner.signCookie(out.toByteArray(), registeredCookie.secret, registeredCookie.mac));
        } catch (IOException | InvalidKeyException | CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> CompletableFuture<T> getCookie(ServerCookieStore cookieStore, Class<T> cookieType) {
        RegisteredCookie registeredCookie = registeredCookies.get(cookieType);
        if (registeredCookie == null) return null;

        CompletableFuture<T> future = new CompletableFuture<>();
        cookieStore.getCookie(registeredCookie.identifier).whenComplete((cookieData, t) -> {
            try {
                byte[] validData = CookieSigner.verifyCookie(cookieData, registeredCookie.secret, registeredCookie.mac);
                if (validData == null) {
                    future.cancel(true);
                    return;
                }

                try (ByteArrayInputStream in = new ByteArrayInputStream(validData)) {
                    future.complete(kryo.readObject(new Input(in), cookieType));
                }
            } catch (Exception e) {
                future.cancel(true);
                throw new RuntimeException(e);
            }
        });
        return future;
    }
}