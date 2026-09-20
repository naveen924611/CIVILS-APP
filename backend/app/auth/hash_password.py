"""Make the password hash for .env:  python -m app.auth.hash_password"""
import getpass

from argon2 import PasswordHasher


def main() -> None:
    pw = getpass.getpass("Choose a strong password: ")
    if pw != getpass.getpass("Type it again: "):
        raise SystemExit("Passwords did not match.")
    if len(pw) < 12:
        raise SystemExit("Use at least 12 characters.")
    print("\nPut this line in your .env (keep the single quotes):\n")
    print(f"OWNER_PASSWORD_HASH='{PasswordHasher().hash(pw)}'")


if __name__ == "__main__":
    main()
