# Expected: two undefined, one type error, one mismatch warning, two call errors.
print(a)

def foo():
    b = 10

print(b)

c = 5 + "hello"

d = 10
d = "world"

def add(x, y):
    return x + y

add(1)
add(1, 2, 3)
