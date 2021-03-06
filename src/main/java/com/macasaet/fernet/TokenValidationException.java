package com.macasaet.fernet;

/**
 * This exception indicates that an operation (e.g. payload decryption) was
 * attempted on an invalid Fernet token.
 *
 * <p>Copyright &copy; 2017 Carlos Macasaet.</p>
 *
 * @author Carlos Macasaet
 */
public class TokenValidationException extends RuntimeException {

    private static final long serialVersionUID = 4917583778149819611L;

    public TokenValidationException(final String message) {
        super(message);
    }

    public TokenValidationException(final Throwable cause) {
        this(cause.getMessage(), cause);
    }

    public TokenValidationException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public TokenValidationException(final String message, final Throwable cause, final boolean enableSuppression,
            final boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }

}