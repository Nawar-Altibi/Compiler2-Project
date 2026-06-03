from flask import Flask, render_template

app = Flask(__name__)

@app.route('/')
def index():
    return render_template('index.html', name="John")

@app.route('/profile')
def profile():
    # missing 'age' for index.html if we were checking it
    return render_template('index.html', username="jdoe")
