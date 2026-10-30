package ma.youscout.mediaworker;

/** Seul ce code contrôlé est conservé ou diffusé ; jamais de message S3/FFmpeg. */
class ProcessingFailure extends RuntimeException {
    final String code;
    final boolean retryable;
    ProcessingFailure(String code, boolean retryable) { super(code); this.code = code; this.retryable = retryable; }
}
