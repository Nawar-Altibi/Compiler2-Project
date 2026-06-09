from flask import Flask, render_template

app = Flask(__name__)

@app.route('/')
def index():
    return render_template('profile.html', name="John")

@app.route('/profile')
def profile():
    # Missing 'username' for profile.html cross-file check
    return render_template('profile.html', name="Jane")
