package com.macasaet.fernet;

import static com.macasaet.fernet.FernetConstants.decoder;
import static com.macasaet.fernet.FernetConstants.encoder;
import static com.macasaet.fernet.FernetConstants.encryptionAlgorithm;
import static com.macasaet.fernet.FernetConstants.encryptionKeyBytes;
import static com.macasaet.fernet.FernetConstants.fernetKeyBytes;
import static com.macasaet.fernet.FernetConstants.signingAlgorithm;
import static com.macasaet.fernet.FernetConstants.signingKeyBytes;
import static com.macasaet.fernet.FernetConstants.tokenPrefixBytes;
import static java.util.Arrays.copyOf;
import static java.util.Arrays.copyOfRange;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64.Encoder;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * A Fernet shared secret key.
 *
 * <p>Copyright &copy; 2017 Carlos Macasaet.</p>
 *
 * @author Carlos Macasaet
 */
public class Key {

	private final byte[] signingKey;
	private final byte[] encryptionKey;

	/**
	 * Create a Key from individual components.
	 *
	 * @param signingKey
	 *            a 128-bit (16 byte) key for signing tokens.
	 * @param encryptionKey
	 *            a 128-bit (16 byte) key for encrypting and decrypting token
	 *            contents.
	 */
	public Key(final byte[] signingKey, final byte[] encryptionKey) {
		if (signingKey == null || signingKey.length != signingKeyBytes) {
			throw new IllegalArgumentException("Signing key must be 128 bits");
		}
		if (encryptionKey == null || encryptionKey.length != encryptionKeyBytes) {
			throw new IllegalArgumentException("Encryption key must be 128 bits");
		}
		this.signingKey = copyOf(signingKey, signingKey.length);
		this.encryptionKey = copyOf(encryptionKey, encryptionKey.length);
	}

	/**
	 * @param string a Base 64 URL string in the format Signing-key (128 bits) || Encryption-key (128 bits)
	 * @return a Fernet key from the specification
	 */
	public static Key fromString(final String string) {
		final byte[] bytes = decoder.decode(string);
		final byte[] signingKey = copyOfRange(bytes, 0, signingKeyBytes);
		final byte[] encryptionKey = copyOfRange(bytes, signingKeyBytes, fernetKeyBytes);
		return new Key(signingKey, encryptionKey);
	}

	/**
	 * Generate a random key
	 *
	 * @param random source of entropy
	 * @return a new shared secret key
	 */
	public static Key generateKey(final Random random) {
		final byte[] signingKey = new byte[signingKeyBytes];
		random.nextBytes(signingKey);
		final byte[] encryptionKey = new byte[encryptionKeyBytes];
		random.nextBytes(encryptionKey);
		return new Key(signingKey, encryptionKey);
	}

	/**
	 * Generate an HMAC signature from the components of a Fernet token.
	 *
	 * @param version the Fernet version number
	 * @param timestamp the seconds after the epoch that the token was generated
	 * @param initializationVector the encryption and decryption initialization vector
	 * @param cipherText the encrypted content of the token
	 * @return the HMAC signature
	 */
	public byte[] getHmac(final byte version, final long timestamp, final IvParameterSpec initializationVector, final byte[] cipherText) {
		try (final ByteArrayOutputStream byteStream = new ByteArrayOutputStream(getTokenPrefixBytes() + cipherText.length)) {
			try (final DataOutputStream dataStream = new DataOutputStream(byteStream)) {
				dataStream.writeByte(version);
				dataStream.writeLong(timestamp);
				dataStream.write(initializationVector.getIV());
				dataStream.write(cipherText);
				dataStream.flush();
	
				try {
					final Mac mac = Mac.getInstance(getSigningAlgorithm());
					try {
						mac.init(getSigningKeySpec());
						return mac.doFinal(byteStream.toByteArray());
					} catch (final InvalidKeyException ike) {
						throw new RuntimeException("Unable to initialise HMAC with shared secret: " + ike.getMessage(), ike);
					}
				} catch (final NoSuchAlgorithmException nsae) {
					// this should not happen as implementors are required to provide the HmacSHA256 algorithm.
					throw new RuntimeException(nsae.getMessage(), nsae);
				}
			}
		} catch (final IOException e) {
			// this should not happen as IO is to memory only
			throw new RuntimeException(e.getMessage(), e);
		}
	}

	public SecretKeySpec getSigningKeySpec() {
		return new SecretKeySpec(getSigningKey(), getSigningAlgorithm());
	}

	public SecretKeySpec getEncryptionKeySpec() {
		return new SecretKeySpec(getEncryptionKey(), getEncryptionAlgorithm());
	}

	/**
	 * @return the Base 64 URL representation of this Fernet key
	 */
	public String serialise() {
		try (final ByteArrayOutputStream byteStream = new ByteArrayOutputStream(fernetKeyBytes)) {
			byteStream.write(getSigningKey());
			byteStream.write(getEncryptionKey());
			return getEncoder().encodeToString(byteStream.toByteArray());
		} catch (final IOException ioe) {
			throw new RuntimeException(ioe.getMessage(), ioe);
		}
	}

	protected byte[] getSigningKey() {
		return signingKey;
	}

	protected byte[] getEncryptionKey() {
		return encryptionKey;
	}

	protected int getTokenPrefixBytes() {
		return tokenPrefixBytes;
	}

	protected String getSigningAlgorithm() {
		return signingAlgorithm;
	}

	protected String getEncryptionAlgorithm() {
		return encryptionAlgorithm;
	}

	protected Encoder getEncoder() {
		return encoder;
	}
}