from flask import Flask, render_template, url_for, request

app = Flask(__name__)

store_name = "Products Store"

products = [
    {"name": "Phone", "price": 300, "description": "A reliable phone"},
    {"name": "Laptop", "price": 800, "description": "A fast laptop"},
    {"name": "Headset", "price": 50, "description": "Comfortable headset"}
]

def product_count(items):
    total = 0
    for item in items:
        total += 1
    return total

@app.route("/")
def index():
    return render_template("index.jinja", title=store_name,
                           products=products,
                           count=product_count(products))

@app.route("/add", methods=["GET", "POST"])
def add_product():
    if request.method == "POST":
        return render_template("index.jinja", title=store_name,
                               products=products,
                               count=product_count(products))
    return render_template("add_product.jinja", title="Add Product")

@app.route("/edit", methods=["GET", "POST"])
def edit_product():
    return render_template("edit_product.jinja", title="Edit Product",
                           product=products[0])

if __name__ == "__main__":
    app.run(debug=True)
