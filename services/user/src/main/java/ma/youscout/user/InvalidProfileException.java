package ma.youscout.user;

/** Signale une donnée de profil invalide, traduite en HTTP 400 par le gestionnaire. */
class InvalidProfileException extends RuntimeException {
    InvalidProfileException(String message) { super(message); }
}
