import 'chat_socket_io.dart'
    if (dart.library.js_interop) 'chat_socket_web.dart'
    as platform;

/// Connexion WebSocket ouverte vers Chat ; [frames] se termine à la fermeture.
abstract class ChatChannel {
  Stream<String> get frames;
  int? get closeCode;
  void send(String frame);
  Future<void> close();
}

typedef ChatConnector = Future<ChatChannel> Function(Uri uri);

/// `dart:io` sur mobile et ordinateur, WebSocket du navigateur sur le web.
Future<ChatChannel> connectChatChannel(Uri uri) => platform.connect(uri);
