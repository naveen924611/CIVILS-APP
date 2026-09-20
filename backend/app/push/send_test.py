"""Send a test push to every registered tablet:  python -m app.push.send_test"""
from app.db.session import get_db
from app.push.fcm import send_to_all


def main() -> None:
    db = next(get_db())
    n = send_to_all(db, "Civils Companion", "Test push: the server can reach your tablet.")
    print(f"Sent to {n} device(s).")


if __name__ == "__main__":
    main()
