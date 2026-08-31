from flask import Flask, render_template, url_for, request

app = Flask(__name__)

store_name = "Products Store"

products = [
    {"id": 1, "name": "Phone", "price": 300, "description": "A reliable phone"},
    {"id": 2, "name": "Laptop", "price": 800, "description": "A fast laptop"},
    {"id": 3, "name": "Headset", "price": 3232, "description": "Comfortable headset"}
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

# ============================================================================
# SEMANTIC ANALYSIS COMMITTEE DEMO (disabled, so the project stays valid)
#
# Activation: replace every "# DEMO " below with an empty string, then Save.
# The watcher will restart from the Lexer and Semantic Analysis will report:
#   1) Undefined Variable       : 2 errors
#   2) Type Error               : 1 error
#   3) Type Mismatch            : 1 warning
#   4) Function Call Error      : 2 errors
#   5) Division by Zero         : 1 error
# Expected total: 6 errors + 1 warning. Code Generation must stop.
# To restore the project, Undo once or add "# DEMO " back to every demo line.
# ----------------------------------------------------------------------------

# DEMO print(undefined_demo_variable)

# DEMO def semantic_scope_demo():
# DEMO     local_only_value = 10

# DEMO print(local_only_value)

# DEMO invalid_math_result = "price" - 5

# DEMO zero_division_result = 100 / 0

# DEMO changing_type = 10
# DEMO changing_type = "ten"

# DEMO def semantic_add(left, right):
# DEMO     return left + right

# DEMO semantic_add(1)
# DEMO semantic_add(1, 2, 3)

# ============================================================================
