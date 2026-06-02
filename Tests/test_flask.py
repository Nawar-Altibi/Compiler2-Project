from flask import Flask, render_template, request, redirect, url_for
import os

app = Flask(__name__)


# -----------------------------------------------------------------------------
# CLASS
# -----------------------------------------------------------------------------
class User:
    def __init__(self, name, age):
        self.name = name
        self.age = age

    def is_adult(self):
        if self.age >= 18:
            return True
        else:
            return False


# -----------------------------------------------------------------------------
# DECORATED FUNCTION (ROUTE)
# -----------------------------------------------------------------------------
@app.route("/")
def index():
    users = [
        User("Ali", 20),
        User("Sara", 17),
        User("Omar", 30)
    ]

    adult_users = []

    # FOR + IF
    for user in users:
        if user.is_adult():
            adult_users.append(user.name)

    return render_template("index.html", users=adult_users)


# -----------------------------------------------------------------------------
# ROUTE WITH METHODS + TRY / EXCEPT / FINALLY
# -----------------------------------------------------------------------------
@app.route("/login", methods=["GET", "POST"])
def login():
    message = ""

    try:
        if request.method == "POST":
            username = request.form["username"]
            password = request.form["password"]

            if username == "admin" and password == "1234":
                return redirect(url_for("dashboard"))
            else:
                message = "Invalid credentials"

    except Exception as e:
        message = "Error occurred"

    finally:
        print("Login attempt finished")

    return render_template("login.html", message=message)


@app.route("/numbers")
def numbers():
    result = []
    i = 0

    while i < 5:
        result.append(i)
        i += 1
    else:
        result.append("done")

    total = 0
    for n in result:
        if n == "done":
            break
        total += n
    else:
        total = -1

    return str(total)


@app.route("/context")
def context_example():
    with app.app_context():
        value = 10
        if value > 5:
            return "Inside app context"
        else:
            return "Small value"


@app.route("/file")
def file_example():
    data = ""

    try:
        with open("data.txt") as f:
            for line in f:
                data += line
    except IOError:
        data = "File not found"

    return data

if __name__ == "__main__":
    app.run(debug=True)
