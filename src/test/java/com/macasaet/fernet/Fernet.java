package com.macasaet.fernet;

import static java.util.Arrays.*;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static javax.crypto.Cipher.DECRYPT_MODE;
import static javax.crypto.Cipher.ENCRYPT_MODE;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Base64.Encoder;
import java.util.Random;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class Fernet {

	private static final Charset charset = Charset.forName("UTF-8");
	private static final Encoder encoder = Base64.getUrlEncoder().withoutPadding();
	private static final Decoder decoder = Base64.getUrlDecoder();
	private static final String encryptionAlgorithm = "AES";
	private static final String signingAlgorithm = "HmacSHA256";

	public static class Key {

		private final byte[] signingKey;
		private final byte[] encryptionKey;

		public Key(final byte[] signingKey, final byte[] encryptionKey) {
			if (signingKey == null || signingKey.length != 16) {
				throw new IllegalArgumentException("Signing key must be 128 bits");
			}
			if (encryptionKey == null || encryptionKey.length != 16) {
				throw new IllegalArgumentException("Encryption key must be 128 bits");
			}
			this.signingKey = signingKey;
			this.encryptionKey = encryptionKey;
		}

		public static Key fromString(final String string) {
			final byte[] bytes = decoder.decode(string);
			final byte[] signingKey = Arrays.copyOfRange(bytes, 0, 16);
			final byte[] encryptionKey = Arrays.copyOfRange(bytes, 16, 32);
			return new Key(signingKey, encryptionKey);
		}

		public byte[] getSigningKey() {
			return signingKey;
		}

		public byte[] getEncryptionKey() {
			return encryptionKey;
		}

		public SecretKeySpec getSigningKeySpec() {
			return new SecretKeySpec(getSigningKey(), signingAlgorithm);
		}

		public SecretKeySpec getEncryptionKeySpec() {
			return new SecretKeySpec(getEncryptionKey(), encryptionAlgorithm);
		}

		public String serialise() {
			try (final ByteArrayOutputStream byteStream = new ByteArrayOutputStream(16 + 16)) {
				byteStream.write(getSigningKey());
				byteStream.write(getEncryptionKey());
				return getEncoder().encodeToString(byteStream.toByteArray());
			} catch (final IOException ioe) {
				throw new RuntimeException(ioe.getMessage(), ioe);
			}
		}

		public byte[] getHmac(final byte version, final long timestamp, final IvParameterSpec initializationVector, final byte[] cipherText) {
			try (final ByteArrayOutputStream byteStream = new ByteArrayOutputStream(1 + 8 + 16 + cipherText.length)) {
				try (final DataOutputStream dataStream = new DataOutputStream(byteStream)) {
					dataStream.writeByte(version);
					dataStream.writeLong(timestamp);
					dataStream.write(initializationVector.getIV());
					dataStream.write(cipherText);
					dataStream.flush();

					try {
						final Mac mac = Mac.getInstance(signingAlgorithm);
						try {
							mac.init(getSigningKeySpec());
							return mac.doFinal(byteStream.toByteArray());
						} catch (final InvalidKeyException ike) {
							throw new RuntimeException(ike.getMessage(), ike);
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

		protected Encoder getEncoder() {
			return encoder;
		}
	}

	public static class Token {

		private static final String cipherTransformation = "AES/CBC/PKCS5Padding";
		private final byte version;
		private final long timestamp;
		private final IvParameterSpec initializationVector;
		private final byte[] cipherText;
		private final byte[] hmac;

		protected Token(final byte version, final long timestamp, final IvParameterSpec initializationVector, final byte[] cipherText, final byte[] hmac) {
			if (version != (byte)0x80) {
				throw new IllegalArgumentException("Unsupported version: " + version);
			}
			if (initializationVector == null || initializationVector.getIV().length != 16) {
				throw new IllegalArgumentException("Initialization Vector must be 128 bits");
			}
			if (cipherText == null || cipherText.length % 16 != 0) {
				throw new IllegalArgumentException("Ciphertext must be a multiple of 128 bits");
			}
			if( hmac == null || hmac.length != 32) {
				throw new IllegalArgumentException("hmac must be 256 bits");
			}
			this.version = version;
			this.timestamp = timestamp;
			this.initializationVector = initializationVector;
			this.cipherText = cipherText;
			this.hmac = hmac;
		}

		protected static Token fromBytes(final byte[] bytes) {
			if (bytes.length < 1 + 8 + 16 + 32) {
				throw new IllegalArgumentException("Not enough bits to generate a Token");
			}
			try (final ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes)) {
				final DataInputStream dataStream = new DataInputStream(inputStream);
				final byte version = dataStream.readByte();
				final long timestamp = dataStream.readLong();

				final byte[] initializationVectorBytes = new byte[16];
				final int ivBytesRead = dataStream.read(initializationVectorBytes);
				if (ivBytesRead < 16) {
					throw new IllegalArgumentException("Not enough bits to generate a Token");
				}

				final byte[] cipherText = new byte[bytes.length - 1 - 8 - 16 - 32];
				final int cipherTextBytesRead = dataStream.read(cipherText);
				if (cipherTextBytesRead < cipherText.length) {
					throw new IllegalArgumentException("Not enough bits to generate a Token");
				}
				final int padLength = Byte.valueOf(cipherText[cipherText.length - 1]).intValue();
				if (padLength > 16) {
					throw new IllegalArgumentException("Padding cannot exceed 16 bytes.");
				}

				final byte[] hmac = new byte[32]; // TODO extract constant
				final int hmacBytesRead = dataStream.read(hmac);
				if (hmacBytesRead < 32) {
					throw new IllegalArgumentException("not enough bits to generate a Token");
				}

				if (dataStream.read() != -1) {
					throw new IllegalArgumentException("more bits found");
				}
				return new Token(version, timestamp, new IvParameterSpec(initializationVectorBytes), cipherText, hmac);
			} catch (final IOException ioe) {
				// this should not happen as I/O is from memory and stream
				// length is verified ahead of time
				throw new RuntimeException(ioe.getMessage(), ioe);
			}
		}

		public static Token fromString(final String string) {
			return fromBytes(decoder.decode(string));
		}

		public Token(final IvParameterSpec initializationVector, final byte[] cipherText, final byte[] hmac) {
			this((byte) 0x80, MILLISECONDS.toSeconds(currentTimeMillis()), initializationVector, cipherText, hmac);
		}

		public Token(final Random random, final byte[] cipherText, final byte[] hmac) {
			this(generateInitializationVector(random), cipherText, hmac);
		}

		protected static IvParameterSpec generateInitializationVector(final Random random) {
			return new IvParameterSpec(generateInitializationVectorBytes(random));
		}

		protected static byte[] generateInitializationVectorBytes(final Random random) {
			final byte[] retval = new byte[16];
			random.nextBytes(retval);
			return retval;
		}

		public static Token generate(final Random random, final Key key, final String plainText)
				throws NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
				InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException {
			final IvParameterSpec initializationVector = generateInitializationVector(random);
			final byte[] cipherText = encrypt(key, plainText, initializationVector);
			final byte[] hmac = key.getHmac((byte)0x80, currentTimeMillis(), initializationVector, cipherText);
			return new Token(initializationVector, cipherText, hmac);
		}

		protected static byte[] encrypt(final Key key, final String string, final IvParameterSpec initializationVector) {
			try {
				final Cipher cipher = Cipher.getInstance(cipherTransformation);
				return encrypt(key, cipher, string, initializationVector);
			} catch (final NoSuchAlgorithmException | NoSuchPaddingException e) {
				// this should not happen
				throw new RuntimeException("Unable to access cipher: " + e.getMessage(), e);
			}
		}

		protected static byte[] encrypt(final Key key, final Cipher cipher, final String string,
				final IvParameterSpec initializationVector) {
			try {
				cipher.init(ENCRYPT_MODE, key.getEncryptionKeySpec(), initializationVector);
				return cipher.doFinal(string.getBytes(charset));
			} catch (final InvalidKeyException | InvalidAlgorithmParameterException e) {
				// this should not happen
				throw new RuntimeException("Unable to initialise cipher: " + e.getMessage(), e);
			} catch (final IllegalBlockSizeException | BadPaddingException e) {
				// this should not happen
				throw new RuntimeException("Unable to encrypt data: " + e.getMessage(), e);
			}
		}

		public boolean isValid(final Key key, final long earliestValidTimestamp, final long latestValidTimestamp) {
			try {
				final Cipher cipher = Cipher.getInstance(cipherTransformation);
				decrypt(cipher, key);
				return isValidVersion()
						&& isNotExpired(earliestValidTimestamp)
						&& isNotTooFarInTheFuture(latestValidTimestamp)
						&& isValidSignature(key);
			} catch (final BadPaddingException e) {
				return false;
			} catch (final NoSuchAlgorithmException | NoSuchPaddingException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}

		protected boolean isValidSignature(final Key key) {
			final byte[] computedHmac = key.getHmac(getVersion(), getTimestamp(), getInitializationVector(),
					getCipherText());
			return Arrays.equals(getHmac(), computedHmac);
		}

		/**
		 * Evaluate whether or not any clock skew is acceptable.
		 *
		 * @param latestValidTimestamp the latest time that this token would be considered valid, expressed in seconds after the epoch
		 * @return true if and only if this token is not too far in the future
		 */
		protected boolean isNotTooFarInTheFuture(final long latestValidTimestamp) {
			return getTimestamp() <= latestValidTimestamp;
		}

		/**
		 * @param earliestValidTimestamp the earliest time that this token would be considered valid, expressed in seconds after the epoch.
		 * @return true if and only if this token is not expired
		 */
		protected boolean isNotExpired(final long earliestValidTimestamp) {
			return getTimestamp() >= earliestValidTimestamp;
		}

		protected boolean isValidVersion() {
			return getVersion() == (byte) 0x80;
		}

		protected String decrypt(final Cipher cipher, final Key key) throws BadPaddingException {
			try {
				cipher.init(DECRYPT_MODE, key.getEncryptionKeySpec(), getInitializationVector());
				final byte[] plainBytes = cipher.doFinal(getCipherText());
				return new String(plainBytes, charset);
			} catch (final InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		}

		public String decrypt(final Key key) {
			try {
				final Cipher cipher = Cipher.getInstance(cipherTransformation);
				return decrypt(cipher, key);
			} catch (final NoSuchAlgorithmException | NoSuchPaddingException | BadPaddingException e) {
				// this should not happen
				throw new RuntimeException(e.getMessage(), e);
			}
		}

		public String getTokenString() {
			try (final ByteArrayOutputStream byteStream = new ByteArrayOutputStream(
					1 + 8 + 16 + getCipherText().length + 32)) {
				try (final DataOutputStream dataStream = new DataOutputStream(byteStream)) {
					dataStream.writeByte(getVersion());
					dataStream.writeLong(getTimestamp());
					dataStream.write(getInitializationVector().getIV());
					dataStream.write(getCipherText());
					dataStream.write(getHmac());
					dataStream.flush();

					return encoder.encodeToString(byteStream.toByteArray());
				}
			} catch (final IOException e) {
				// this should not happen as IO is to memory only
				throw new RuntimeException(e.getMessage(), e);
			}
		}

		public byte getVersion() {
			return version;
		}

		/**
		 * @return the number of seconds after the epoch that this token was generated
		 */
		public long getTimestamp() {
			return timestamp;
		}

		public IvParameterSpec getInitializationVector() {
			return initializationVector;
		}

		public byte[] getCipherText() {
			return cipherText;
		}

		public byte[] getUnpaddedCipherText() {
			final byte[] original = getCipherText();
			final byte pad = original[original.length - 1]; // should be less
															// than 16
			if (pad > 16) {
				throw new IllegalStateException("pad octet exceeds 16");
			} else if( pad < 1 ) {
				throw new IllegalStateException("all cipher text must be padded");
			}
			final int unpaddedLength = original.length - pad;
			return copyOfRange(original, 0, unpaddedLength);
		}

		public byte[] getHmac() {
			return hmac;
		}

		public String toString() {
			final StringBuilder builder = new StringBuilder();
			final byte[] ivBytes = getInitializationVector().getIV();
			builder.append("Token [version=").append(String.format("0x%x", new BigInteger(1, new byte[] { getVersion() })))
					.append(", timestamp=").append(toDateString(getTimestamp()))
					.append(", initializationVector=").append(toBase64String(ivBytes))
					.append(", cipherText=").append(encoder.encodeToString(getCipherText()))
					.append(", hmac=").append(encoder.encodeToString(getHmac())).append("]");
			return builder.toString();
		}

		protected static String toDateString(final long secondsSinceEpoch) {
			return Instant.ofEpochSecond(secondsSinceEpoch).toString();
		}

		protected static String toBase64String(final byte[] input) {
			return encoder.encodeToString(input);
		}

		protected static String toHexArrayString(final byte[] input) {
			final StringBuilder builder = new StringBuilder("[ ");
			for( int i = 0; i < input.length - 1; i++ ) {
				builder.append(String.format("%#x", input[ i ])).append(", ");
			}
			builder.append(String.format("%#x", input[ input.length - 1 ]));
			builder.append( " ]" );
			return builder.toString();
		}

		protected static String toIntArrayString(final byte[] input) {
			final StringBuilder builder = new StringBuilder("[ ");
			for( int i = 0; i < input.length - 1; i++) {
				builder.append(Byte.valueOf(input[ i ]).intValue()).append(", ");
			}
			builder.append(Byte.valueOf(input[ input.length - 1]).intValue());
			builder.append(" ]");
			return builder.toString();
		}
	}

}