"""SafeTap data model. Matches the spec's minimum tables, plus notifications
(delivery receipts per contact per channel) and tokens for simple auth."""

import datetime
import uuid

from sqlalchemy import (Boolean, Column, DateTime, Float, ForeignKey, Integer,
                        String, Text, create_engine)
from sqlalchemy.orm import declarative_base, relationship, sessionmaker

from . import config

Base = declarative_base()


def _now():
    return datetime.datetime.utcnow()


def _uid():
    return str(uuid.uuid4())


class User(Base):
    __tablename__ = "users"
    id = Column(String, primary_key=True, default=_uid)
    phone = Column(String, unique=True, index=True, nullable=False)  # +234...
    name = Column(String, nullable=False)
    photo_url = Column(String, default="")
    pin_hash = Column(String, nullable=False)
    medical_enc = Column(Text, default="")  # Fernet-encrypted JSON, see security.py
    created_at = Column(DateTime, default=_now)

    contacts = relationship("Contact", backref="user", cascade="all, delete-orphan")


class Token(Base):
    __tablename__ = "tokens"
    token = Column(String, primary_key=True)
    user_id = Column(String, ForeignKey("users.id"), index=True)
    created_at = Column(DateTime, default=_now)


class Contact(Base):
    __tablename__ = "contacts"
    id = Column(String, primary_key=True, default=_uid)
    user_id = Column(String, ForeignKey("users.id"), index=True)
    name = Column(String, nullable=False)
    phone = Column(String, nullable=False)  # +234...
    relationship = Column(String, default="")
    priority = Column(Integer, default=0)
    sms_enabled = Column(Boolean, default=True)
    voice_enabled = Column(Boolean, default=True)
    whatsapp_enabled = Column(Boolean, default=True)


class Alert(Base):
    __tablename__ = "alerts"
    id = Column(String, primary_key=True, default=_uid)
    user_id = Column(String, ForeignKey("users.id"), index=True)
    type = Column(String, default="panic")  # panic | checkin_missed
    status = Column(String, default="TRIGGERED")  # TRIGGERED -> CONTACTS_NOTIFIED -> ACKNOWLEDGED -> RESOLVED | FALSE_ALARM
    tracking_token = Column(String, unique=True, index=True)
    expires_at = Column(DateTime)
    started_at = Column(DateTime, default=_now)
    notified_at = Column(DateTime, nullable=True)
    acknowledged_at = Column(DateTime, nullable=True)
    resolved_at = Column(DateTime, nullable=True)
    false_alarm_at = Column(DateTime, nullable=True)
    last_lat = Column(Float, nullable=True)
    last_lng = Column(Float, nullable=True)
    last_battery = Column(Integer, nullable=True)

    ACTIVE = ("TRIGGERED", "CONTACTS_NOTIFIED", "ACKNOWLEDGED")


class LocationPing(Base):
    __tablename__ = "location_pings"
    id = Column(String, primary_key=True, default=_uid)
    alert_id = Column(String, ForeignKey("alerts.id"), index=True)
    lat = Column(Float, nullable=False)
    lng = Column(Float, nullable=False)
    battery = Column(Integer, nullable=True)
    recorded_at = Column(DateTime, default=_now)


class Acknowledgement(Base):
    __tablename__ = "acknowledgements"
    id = Column(String, primary_key=True, default=_uid)
    alert_id = Column(String, ForeignKey("alerts.id"), index=True)
    contact_id = Column(String, ForeignKey("contacts.id"), nullable=True)
    actor_name = Column(String, default="")
    action = Column(String, default="on_my_way")  # on_my_way
    at = Column(DateTime, default=_now)


class Notification(Base):
    """One row per contact per channel per alert: the delivery receipt log."""
    __tablename__ = "notifications"
    id = Column(String, primary_key=True, default=_uid)
    alert_id = Column(String, ForeignKey("alerts.id"), index=True)
    contact_id = Column(String, ForeignKey("contacts.id"))
    channel = Column(String)  # sms | voice | whatsapp
    status = Column(String, default="queued")  # queued | sent | ringing | delivered | failed
    detail = Column(String, default="")
    sent_at = Column(DateTime, default=_now)


class CheckIn(Base):
    """Armed check-in timer. Server fires SOS at checkin_at + grace if not confirmed."""
    __tablename__ = "checkins"
    id = Column(String, primary_key=True, default=_uid)
    user_id = Column(String, ForeignKey("users.id"), index=True)
    checkin_at = Column(DateTime, nullable=False)
    confirmed = Column(Boolean, default=False)
    fired = Column(Boolean, default=False)
    note = Column(String, default="")
    created_at = Column(DateTime, default=_now)


engine = create_engine(config.DATABASE_URL, connect_args={"check_same_thread": False})
SessionLocal = sessionmaker(bind=engine, autoflush=False, autocommit=False,
                            expire_on_commit=False)


def init_db():
    Base.metadata.create_all(engine)
