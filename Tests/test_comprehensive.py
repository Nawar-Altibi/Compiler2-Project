# app.py

from flask import Flask, render_template, request, redirect, url_for
from products import get_all_products, get_product, add_product, delete_product
import os
from werkzeug.utils import secure_filename

# 1. Application setup
app = Flask(__name__)

# 2. Image upload folder configuration
UPLOAD_FOLDER = 'static/images'
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER

# 3. Home route: display all products
@app.route('/')
def products_page():
    products = get_all_products()
    return render_template('products.html', products=products)

# 4. Route to display a single product details
@app.route('/product/<int:product_id>')
def product_detail_page(product_id):
    product = get_product(product_id)
    if product:
        return render_template('product_detail.html', product=product)
    return "Product not found", 404

# 5. Route to add a new product (GET to show form, POST to save data)
@app.route('/add', methods=['GET', 'POST'])
def add_product_page():
    if request.method == 'POST':
        name = request.form['name']
        price = float(request.form['price'])
        details = request.form['details']

        # Handle uploaded image
        image_file = request.files.get('image')
        filename = "default.png"  # Default image

        if image_file and image_file.filename:
            # Secure the filename and save it
            filename = secure_filename(image_file.filename)
            # Ensure the upload directory exists before saving
            if not os.path.exists(app.config['UPLOAD_FOLDER']):
                os.makedirs(app.config['UPLOAD_FOLDER'])
            image_file.save(os.path.join(app.config['UPLOAD_FOLDER'], filename))

        # Add product to the dummy database
        add_product(name, price, details, filename)

        return redirect(url_for('products_page'))

    return render_template('add_product.html')

# 6. Route to delete a product (must be POST)
@app.route('/delete/<int:product_id>', methods=['POST'])
def delete_product_route(product_id):
    delete_product(product_id)
    return redirect(url_for('products_page'))

# 7. Run the application
if __name__ == '__main__':
    # Ensure the image upload directory exists
    if not os.path.exists(UPLOAD_FOLDER):
        os.makedirs(UPLOAD_FOLDER)

    app.run(debug=True)
