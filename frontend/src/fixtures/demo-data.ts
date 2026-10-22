// Demo data shared by the mock backend (design D10) and the seed script (design D14),
// so the mock and a seeded live stack show the same catalog.
// Passwords are fixed demo values for local stacks only (documented in README.md).

import type { Role } from '../api/auth';

export interface DemoAccount {
  email: string;
  password: string;
  role: Role;
  label: string;
}

export interface DemoPackaging {
  grossWeightGrams: number;
  lengthCm: number;
  widthCm: number;
  heightCm: number;
}

export interface DemoProduct {
  title: string;
  description: string;
  category: DemoCategoryName;
  /** UAH. */
  price: number;
  packaging: DemoPackaging;
  /** Available quantity the seed script adjusts stock to. */
  targetStock: number;
  /** Activated by the seed script; inactive ones are hidden from the storefront. */
  active: boolean;
}

/**
 * The admin is created by Auth Service at startup (EP-AUTH-JOB-01) with the
 * stack's ADMIN_PASSWORD; this password is used by the mock backend only.
 * The seed script creates the other three accounts.
 */
export const DEMO_ADMIN: DemoAccount = {
  email: 'admin@voltstore.com',
  password: 'admin-demo',
  role: 'ADMIN',
  label: 'Admin',
};

export const DEMO_STAFF: DemoAccount[] = [
  {
    email: 'manager@voltstore.com',
    password: 'manager-demo',
    role: 'CATALOG_MANAGER',
    label: 'Catalog manager',
  },
  {
    email: 'worker@voltstore.com',
    password: 'worker-demo',
    role: 'INVENTORY_WORKER',
    label: 'Inventory worker',
  },
];

export const DEMO_CUSTOMER: DemoAccount = {
  email: 'customer@voltstore.com',
  password: 'customer-demo',
  role: 'CUSTOMER',
  label: 'Customer',
};

export const DEMO_ACCOUNTS: DemoAccount[] = [DEMO_ADMIN, ...DEMO_STAFF, DEMO_CUSTOMER];

export const DEMO_CATEGORIES = ['Accessories', 'Audio', 'Laptops', 'Phones'] as const;
export type DemoCategoryName = (typeof DEMO_CATEGORIES)[number];

const phone = (g: number): DemoPackaging => ({
  grossWeightGrams: g,
  lengthCm: 18,
  widthCm: 10,
  heightCm: 6,
});
const laptop = (g: number): DemoPackaging => ({
  grossWeightGrams: g,
  lengthCm: 42,
  widthCm: 30,
  heightCm: 8,
});
const audio = (g: number): DemoPackaging => ({
  grossWeightGrams: g,
  lengthCm: 26,
  widthCm: 21,
  heightCm: 10,
});
const small = (g: number): DemoPackaging => ({
  grossWeightGrams: g,
  lengthCm: 16,
  widthCm: 11,
  heightCm: 5,
});

export const DEMO_PRODUCTS: DemoProduct[] = [
  // Phones
  {
    title: 'Apple iPhone 16, 128 GB, Black',
    description:
      '6.1-inch Super Retina XDR display, A18 chip and a 48 MP main camera. USB-C charging and up to 22 hours of video playback.',
    category: 'Phones',
    price: 37999,
    packaging: phone(420),
    targetStock: 14,
    active: true,
  },
  {
    title: 'Apple iPhone 16 Pro, 256 GB, Natural Titanium',
    description:
      'Titanium design, 6.3-inch ProMotion display, A18 Pro chip and a 5x telephoto camera. Records 4K video at 120 fps.',
    category: 'Phones',
    price: 54999,
    packaging: phone(450),
    targetStock: 6,
    active: true,
  },
  {
    title: 'Samsung Galaxy A56 5G, 256 GB',
    description:
      '6.7-inch Super AMOLED display at 120 Hz, a 5000 mAh battery and six years of software updates.',
    category: 'Phones',
    price: 17499,
    packaging: phone(410),
    targetStock: 25,
    active: true,
  },
  {
    title: 'Samsung Galaxy S25, 256 GB, Navy',
    description:
      'Compact flagship with a 6.2-inch display, Snapdragon 8 Elite and a triple camera with 3x optical zoom.',
    category: 'Phones',
    price: 39999,
    packaging: phone(400),
    targetStock: 9,
    active: true,
  },
  {
    title: 'Google Pixel 9a, 128 GB, Obsidian',
    description:
      'Clean Android with seven years of updates, a 6.3-inch display and the Tensor G4 chip. IP68 water and dust resistance.',
    category: 'Phones',
    price: 21999,
    packaging: phone(390),
    targetStock: 0,
    active: true,
  },
  {
    title: 'Xiaomi Redmi Note 14 Pro, 256 GB',
    description:
      'A 200 MP main camera, 6.67-inch AMOLED display and 45 W fast charging in an IP64 body.',
    category: 'Phones',
    price: 13999,
    packaging: phone(400),
    targetStock: 30,
    active: true,
  },
  {
    title: 'Motorola Edge 50 Fusion, 256 GB',
    description: 'Curved 6.7-inch pOLED display, 68 W charging and a vegan leather back.',
    category: 'Phones',
    price: 14499,
    packaging: phone(390),
    targetStock: 12,
    active: true,
  },
  {
    title: 'OnePlus 13R, 256 GB, Nebula Noir',
    description:
      'Snapdragon 8 Gen 3, a 6000 mAh battery with 80 W charging and a bright 6.78-inch display.',
    category: 'Phones',
    price: 24999,
    packaging: phone(420),
    targetStock: 7,
    active: true,
  },
  {
    title: 'Nothing Phone (3a), 256 GB',
    description:
      'Glyph interface on a transparent back, 6.77-inch AMOLED and a 50 MP periscope camera.',
    category: 'Phones',
    price: 16999,
    packaging: phone(400),
    targetStock: 10,
    active: true,
  },
  {
    title: 'Samsung Galaxy Z Flip6, 256 GB',
    description: 'Foldable phone with a 3.4-inch cover screen and a 6.7-inch main display.',
    category: 'Phones',
    price: 42999,
    packaging: phone(430),
    targetStock: 3,
    active: true,
  },
  {
    title: 'Apple iPhone 15, 128 GB, Blue',
    description: 'Dynamic Island, a 48 MP main camera and USB-C on a 6.1-inch phone.',
    category: 'Phones',
    price: 31999,
    packaging: phone(410),
    targetStock: 0,
    active: true,
  },
  {
    title: 'Xiaomi 14T, 512 GB',
    description: 'Leica-tuned cameras, a 144 Hz display and 67 W charging.',
    category: 'Phones',
    price: 23999,
    packaging: phone(420),
    targetStock: 5,
    active: false,
  },

  // Laptops
  {
    title: 'ASUS Vivobook 15 OLED, Core i5, 16 GB',
    description:
      '15.6-inch OLED display, Intel Core i5-13500H, 16 GB RAM and a 512 GB SSD. Weighs 1.7 kg.',
    category: 'Laptops',
    price: 27499,
    packaging: laptop(2600),
    targetStock: 0,
    active: true,
  },
  {
    title: 'Lenovo IdeaPad Slim 5 14", Ryzen 7',
    description:
      'AMD Ryzen 7 8845HS, 16 GB RAM, 1 TB SSD and a 14-inch 2.8K OLED screen in an aluminium body.',
    category: 'Laptops',
    price: 32999,
    packaging: laptop(2400),
    targetStock: 8,
    active: true,
  },
  {
    title: 'Apple MacBook Air 13", M3, 16 GB, 256 GB',
    description:
      'Fanless design with the M3 chip, a 13.6-inch Liquid Retina display and up to 18 hours of battery life.',
    category: 'Laptops',
    price: 49999,
    packaging: laptop(2100),
    targetStock: 11,
    active: true,
  },
  {
    title: 'Apple MacBook Pro 14", M4 Pro, 24 GB, 512 GB',
    description: 'Liquid Retina XDR display, M4 Pro with a 14-core CPU and Thunderbolt 5 ports.',
    category: 'Laptops',
    price: 94999,
    packaging: laptop(2900),
    targetStock: 4,
    active: true,
  },
  {
    title: 'HP Pavilion 15, Core i7, 16 GB, 1 TB',
    description: 'A 15.6-inch Full HD IPS display, Intel Core i7-1355U and a full-size keyboard.',
    category: 'Laptops',
    price: 34999,
    packaging: laptop(2700),
    targetStock: 6,
    active: true,
  },
  {
    title: 'Acer Nitro V 15, RTX 4050, Core i5',
    description: 'Gaming laptop with GeForce RTX 4050, a 144 Hz display and 16 GB DDR5 memory.',
    category: 'Laptops',
    price: 38999,
    packaging: laptop(3300),
    targetStock: 9,
    active: true,
  },
  {
    title: 'Dell Inspiron 14, Core Ultra 5, 16 GB',
    description:
      'Intel Core Ultra 5 with an AI engine, a 14-inch 16:10 display and a 1080p webcam.',
    category: 'Laptops',
    price: 36999,
    packaging: laptop(2500),
    targetStock: 5,
    active: true,
  },
  {
    title: 'Microsoft Surface Laptop 7, 13.8", Snapdragon X Plus',
    description: 'Copilot+ PC with a touch display, up to 20 hours of video playback and Wi-Fi 7.',
    category: 'Laptops',
    price: 52999,
    packaging: laptop(2400),
    targetStock: 2,
    active: true,
  },
  {
    title: 'ASUS ROG Zephyrus G14, RTX 4060',
    description: 'A 14-inch 3K OLED at 120 Hz, Ryzen 9 and GeForce RTX 4060 in a 1.5 kg chassis.',
    category: 'Laptops',
    price: 79999,
    packaging: laptop(2800),
    targetStock: 3,
    active: true,
  },
  {
    title: 'Lenovo ThinkPad E14 Gen 6, Ryzen 5',
    description:
      'Business laptop with a spill-resistant keyboard, fingerprint reader and 16 GB RAM.',
    category: 'Laptops',
    price: 33999,
    packaging: laptop(2500),
    targetStock: 0,
    active: false,
  },

  // Audio
  {
    title: 'Sony WH-1000XM5 headphones',
    description:
      'Over-ear wireless headphones with adaptive noise cancelling and up to 30 hours of playback. Connects to two devices at once.',
    category: 'Audio',
    price: 12999,
    packaging: audio(420),
    targetStock: 18,
    active: true,
  },
  {
    title: 'Apple AirPods Pro 2, USB-C',
    description:
      'Active noise cancellation, adaptive audio and a MagSafe charging case with a speaker.',
    category: 'Audio',
    price: 9999,
    packaging: small(180),
    targetStock: 22,
    active: true,
  },
  {
    title: 'JBL Flip 6 portable speaker',
    description: 'Waterproof and dustproof (IP67) Bluetooth speaker with 12 hours of playtime.',
    category: 'Audio',
    price: 4299,
    packaging: audio(750),
    targetStock: 16,
    active: true,
  },
  {
    title: 'Samsung Galaxy Buds3 Pro',
    description: 'Blade-design earbuds with intelligent noise cancelling and 24-bit Hi-Fi sound.',
    category: 'Audio',
    price: 8499,
    packaging: small(160),
    targetStock: 0,
    active: true,
  },
  {
    title: 'Xiaomi Redmi Buds 6 Pro',
    description:
      'Hybrid noise cancelling up to 55 dB, dual drivers and up to 36 hours with the case.',
    category: 'Audio',
    price: 2499,
    packaging: small(150),
    targetStock: 40,
    active: true,
  },
  {
    title: 'Marshall Emberton III speaker',
    description:
      'Compact speaker with 360-degree sound, 32+ hours of playtime and IP67 protection.',
    category: 'Audio',
    price: 7499,
    packaging: audio(900),
    targetStock: 7,
    active: true,
  },
  {
    title: 'Bose QuietComfort Ultra headphones',
    description: 'Immersive spatial audio and world-class noise cancelling in a foldable design.',
    category: 'Audio',
    price: 17999,
    packaging: audio(480),
    targetStock: 4,
    active: true,
  },
  {
    title: 'Sennheiser Momentum 4 Wireless',
    description:
      'Up to 60 hours of battery life, adaptive noise cancellation and audiophile sound.',
    category: 'Audio',
    price: 13499,
    packaging: audio(450),
    targetStock: 6,
    active: true,
  },
  {
    title: 'JBL Tune 520BT headphones',
    description: 'Light on-ear headphones with JBL Pure Bass sound and 57 hours of playtime.',
    category: 'Audio',
    price: 1799,
    packaging: audio(300),
    targetStock: 35,
    active: true,
  },
  {
    title: 'Sonos Era 100 speaker',
    description: 'Wi-Fi and Bluetooth smart speaker with stereo sound and Trueplay tuning.',
    category: 'Audio',
    price: 11999,
    packaging: audio(2600),
    targetStock: 3,
    active: true,
  },
  {
    title: 'Anker Soundcore Liberty 4 NC',
    description: 'Adaptive noise cancelling earbuds with up to 50 hours of playtime and LDAC.',
    category: 'Audio',
    price: 3299,
    packaging: small(170),
    targetStock: 20,
    active: true,
  },

  // Accessories
  {
    title: 'Anker 737 power bank, 24,000 mAh',
    description: '140 W output, a smart display and charging for a laptop and a phone at once.',
    category: 'Accessories',
    price: 4899,
    packaging: small(700),
    targetStock: 13,
    active: true,
  },
  {
    title: 'Baseus 65 W GaN charger, 3 ports',
    description: 'Two USB-C and one USB-A port; charges a laptop at full speed.',
    category: 'Accessories',
    price: 1599,
    packaging: small(220),
    targetStock: 0,
    active: true,
  },
  {
    title: 'Ugreen USB-C cable 2 m, 100 W',
    description: 'Braided USB-C to USB-C cable for 100 W charging and 480 Mbps data.',
    category: 'Accessories',
    price: 399,
    packaging: small(90),
    targetStock: 60,
    active: true,
  },
  {
    title: 'Apple MagSafe charger, 1 m',
    description: 'Magnetic wireless charging up to 25 W for recent iPhone models.',
    category: 'Accessories',
    price: 1999,
    packaging: small(150),
    targetStock: 15,
    active: true,
  },
  {
    title: 'Logitech MX Master 3S mouse',
    description:
      'Quiet clicks, an 8000 DPI sensor and MagSpeed scrolling; pairs with three devices.',
    category: 'Accessories',
    price: 4299,
    packaging: small(350),
    targetStock: 12,
    active: true,
  },
  {
    title: 'Logitech MX Keys S keyboard',
    description: 'Backlit low-profile keyboard with smart illumination and multi-device switching.',
    category: 'Accessories',
    price: 4999,
    packaging: laptop(1100),
    targetStock: 8,
    active: true,
  },
  {
    title: 'Samsung 45 W USB-C charger',
    description: 'Super Fast Charging 2.0 wall charger with PPS support.',
    category: 'Accessories',
    price: 1199,
    packaging: small(200),
    targetStock: 25,
    active: true,
  },
  {
    title: 'Spigen Ultra Hybrid case for iPhone 16',
    description: 'Clear shock-absorbing case with raised edges for the screen and camera.',
    category: 'Accessories',
    price: 699,
    packaging: small(80),
    targetStock: 45,
    active: true,
  },
  {
    title: 'Xiaomi Smart Band 9',
    description: 'Fitness tracker with an AMOLED display, 21-day battery and 150+ workout modes.',
    category: 'Accessories',
    price: 1699,
    packaging: small(160),
    targetStock: 18,
    active: true,
  },
  {
    title: 'SanDisk Extreme 1 TB portable SSD',
    description: 'Rugged USB-C SSD with up to 1050 MB/s read speed and IP65 protection.',
    category: 'Accessories',
    price: 4799,
    packaging: small(180),
    targetStock: 9,
    active: true,
  },
  {
    title: 'TP-Link Archer AX55 Wi-Fi 6 router',
    description: 'Dual-band AX3000 router with four antennas and OneMesh support.',
    category: 'Accessories',
    price: 2999,
    packaging: laptop(1200),
    targetStock: 6,
    active: true,
  },
  {
    title: 'Belkin 3-in-1 MagSafe stand',
    description: 'Charges an iPhone, an Apple Watch and AirPods at the same time.',
    category: 'Accessories',
    price: 5499,
    packaging: small(800),
    targetStock: 0,
    active: false,
  },
];

export interface DemoCartLine {
  /** A fixture product title; the mock and the seed resolve it to the product id. */
  title: string;
  quantity: number;
}

/**
 * The demo customer's cart (change customer-cart, design D10): two in-stock lines
 * and one inactive product, so the cart page shows an unavailable line and a
 * guest who logs in as the demo customer is asked to replace, keep or merge.
 */
export const DEMO_CART: DemoCartLine[] = [
  { title: 'Sony WH-1000XM5 headphones', quantity: 1 },
  { title: 'Anker 737 power bank, 24,000 mAh', quantity: 2 },
  { title: 'Xiaomi 14T, 512 GB', quantity: 1 },
];
