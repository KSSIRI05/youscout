import 'auth.dart';
import 'config.dart';
import 'auth_native.dart'
    if (dart.library.js_interop) 'auth_web.dart'
    as platform;

AuthDriver createAuthDriver(BackendConfig config) =>
    platform.createAuthDriver(config);
