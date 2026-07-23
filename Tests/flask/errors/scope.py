# Expected: y, val, x and instance are undefined.
def outer():
    x = 1
    def inner():
        y = 2
        print(x)

    print(y)

class MyClass:
    def __init__(self, val):
        self.val = val

    def method(self):
        print(val)

print(x)
print(instance.val)
