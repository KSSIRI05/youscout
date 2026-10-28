import 'dart:async';
import 'dart:js_interop';
import 'package:image_picker/image_picker.dart';
import 'package:web/web.dart' as web;
import '../backend/api.dart';

/// XHR fournit la progression réseau réelle. Le navigateur fixe Content-Length.
Future<void> putVideo(
  XFile file,
  Uri url,
  String type,
  void Function(double) progress,
) async {
  final bytes = await file.readAsBytes();
  final xhr = web.XMLHttpRequest();
  final done = Completer<void>();
  void fail() {
    if (!done.isCompleted) {
      done.completeError(
        const ApiFailure(
          503,
          'Transfert interrompu ou refusé par S3. Vérifie le réseau et réessaie.',
        ),
      );
    }
  }

  xhr.open('PUT', url.toString());
  xhr.timeout = 600000;
  xhr.withCredentials = false;
  xhr.setRequestHeader('Content-Type', type);
  xhr.upload.onprogress = ((web.ProgressEvent event) {
    if (event.lengthComputable && event.total > 0) {
      progress(event.loaded / event.total);
    }
  }).toJS;
  xhr.onload = ((web.Event _) {
    if (xhr.status >= 200 && xhr.status < 300) {
      if (!done.isCompleted) done.complete();
    } else {
      fail();
    }
  }).toJS;
  xhr.onerror = ((web.Event _) => fail()).toJS;
  xhr.ontimeout = ((web.Event _) => fail()).toJS;
  xhr.onabort = ((web.Event _) => fail()).toJS;
  xhr.send(bytes.toJS);
  await done.future;
}
