"""Exercise Social through the Gateway and its calls to Video and User; nothing is written."""
import uuid
from catalogue_smoke import request

def exercise(player_token, player_subject):
    video = str(uuid.uuid4())
    # Lectures : une vidéo sans commentaire renvoie une page vide, les tailles hors bornes sont refusées.
    status, comments = request("GET", f"/v1/social/videos/{video}/comments", player_token)
    assert status == 200 and comments == [], f"Social comment page failed (HTTP {status})"
    assert request("GET", f"/v1/social/videos/{video}/comments?limit=0", player_token)[0] == 400, "Invalid comment page size accepted"
    # Écritures : Social interroge Video, puis User ; les erreurs ne doivent plus être masquées en 403.
    status, _ = request("POST", f"/v1/social/videos/{video}/comments", player_token, {"body": "Smoke"})
    assert status == 404, f"Comment on an unpublished video was not rejected by Video (HTTP {status})"
    assert request("PUT", f"/v1/social/videos/{video}/likes", player_token)[0] == 404, "Like on an unpublished video accepted"
    status, _ = request("POST", "/v1/social/messages", player_token, {"recipientSubject": "missing-" + uuid.uuid4().hex, "body": "Smoke"})
    assert status == 404, f"Message to an unknown profile was not rejected by User (HTTP {status})"
    assert request("POST", "/v1/social/messages", player_token, {"recipientSubject": player_subject, "body": "Smoke"})[0] == 400, "Self-message accepted"
