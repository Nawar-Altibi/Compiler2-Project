# Cross-file: MISSING_TEMPLATE_VARIABLE
# Run this file — compiler also analyzes jinja_complex_errors.html in the same folder.
# Expects: variables used in template but not passed via render_template()

from flask import Flask, render_template

app = Flask(__name__)

@app.route('/')
def home():
    return render_template('jinja_complex_errors.html', show_sidebar=True)

@app.route('/test_scope')
def test_scope():
    x = 10
    if x > 5:
        y = 20
    print(y)
    return "Check console"
