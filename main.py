"""
Simple Android App Example
A complete template for creating an APK with Buildozer
"""

import os
import sys
import json
import time
import threading
from datetime import datetime

# Try importing Android-specific modules (only available on Android)
try:
    from android.permissions import request_permissions, Permission
    from android.storage import app_storage_path
    ANDROID_MODE = True
except ImportError:
    ANDROID_MODE = False
    
# Try importing Plyer for cross-platform hardware access
try:
    from plyer import vibrator, notification, battery, storagepath
    PLYER_AVAILABLE = True
except ImportError:
    PLYER_AVAILABLE = False

# Kivy imports
from kivy.app import App
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.textinput import TextInput
from kivy.uix.scrollview import ScrollView
from kivy.uix.popup import Popup
from kivy.clock import Clock
from kivy.core.window import Window
from kivy.utils import platform


class DataManager:
    """Handles all data storage operations"""
    
    def __init__(self):
        self.data_file = self._get_data_path()
        self.data = self._load_data()
        
    def _get_data_path(self):
        """Get the appropriate data path for each platform"""
        if ANDROID_MODE:
            try:
                # Use app-specific storage on Android
                storage_path = app_storage_path()
                return os.path.join(storage_path, 'app_data.json')
            except:
                pass
        elif PLYER_AVAILABLE:
            try:
                # Use Plyer's storage path for cross-platform
                return os.path.join(storagepath.get_documents_dir(), 'app_data.json')
            except:
                pass
        
        # Fallback for desktop/testing
        return os.path.join(os.path.dirname(__file__), 'app_data.json')
    
    def _load_data(self):
        """Load data from JSON file"""
        default_data = {
            'notes': [],
            'settings': {
                'username': 'User',
                'theme': 'light',
                'auto_save': True
            },
            'stats': {
                'app_opens': 0,
                'last_open': None,
                'total_notes': 0
            }
        }
        
        try:
            if os.path.exists(self.data_file):
                with open(self.data_file, 'r') as f:
                    loaded_data = json.load(f)
                    # Merge with defaults to ensure all keys exist
                    for key in default_data:
                        if key not in loaded_data:
                            loaded_data[key] = default_data[key]
                    return loaded_data
        except Exception as e:
            print(f"Error loading data: {e}")
        
        return default_data
    
    def save_data(self):
        """Save data to JSON file"""
        try:
            # Update stats
            self.data['stats']['last_open'] = datetime.now().isoformat()
            
            # Ensure directory exists
            os.makedirs(os.path.dirname(self.data_file), exist_ok=True)
            
            with open(self.data_file, 'w') as f:
                json.dump(self.data, f, indent=2)
            return True
        except Exception as e:
            print(f"Error saving data: {e}")
            return False
    
    def add_note(self, note_text):
        """Add a new note"""
        note = {
            'id': len(self.data['notes']) + 1,
            'text': note_text,
            'timestamp': datetime.now().isoformat(),
            'edited': False
        }
        self.data['notes'].append(note)
        self.data['stats']['total_notes'] = len(self.data['notes'])
        self.save_data()
        return note
    
    def delete_note(self, note_id):
        """Delete a note by ID"""
        self.data['notes'] = [n for n in self.data['notes'] if n['id'] != note_id]
        self.data['stats']['total_notes'] = len(self.data['notes'])
        self.save_data()
    
    def get_notes(self):
        """Get all notes"""
        return self.data['notes']
    
    def update_setting(self, key, value):
        """Update a specific setting"""
        self.data['settings'][key] = value
        self.save_data()


class NoteWidget(BoxLayout):
    """Widget for displaying a single note"""
    
    def __init__(self, note, on_delete_callback, **kwargs):
        super().__init__(**kwargs)
        self.note = note
        self.on_delete_callback = on_delete_callback
        self.orientation = 'horizontal'
        self.size_hint_y = None
        self.height = 50
        self.padding = [5, 2]
        self.spacing = 5
        
        # Note text label
        preview = note['text'][:50] + ('...' if len(note['text']) > 50 else '')
        self.note_label = Label(
            text=f"#{note['id']} {preview}",
            size_hint_x=0.7,
            halign='left',
            valign='middle'
        )
        self.note_label.bind(size=self.note_label.setter('text_size'))
        
        # Delete button
        delete_btn = Button(
            text='X',
            size_hint_x=0.15,
            background_color=(0.9, 0.2, 0.2, 1),
            color=(1, 1, 1, 1)
        )
        delete_btn.bind(on_release=self.delete_note)
        
        # View button
        view_btn = Button(
            text='View',
            size_hint_x=0.15,
            background_color=(0.2, 0.6, 0.9, 1),
            color=(1, 1, 1, 1)
        )
        view_btn.bind(on_release=self.view_note)
        
        self.add_widget(self.note_label)
        self.add_widget(view_btn)
        self.add_widget(delete_btn)
    
    def delete_note(self, instance):
        """Handle note deletion"""
        self.on_delete_callback(self.note['id'])
    
    def view_note(self, instance):
        """Show full note in popup"""
        content = BoxLayout(orientation='vertical', padding=10, spacing=10)
        note_text = TextInput(
            text=self.note['text'],
            readonly=True,
            size_hint_y=0.8,
            background_color=(0.95, 0.95, 0.95, 1)
        )
        close_btn = Button(
            text='Close',
            size_hint_y=0.2,
            background_color=(0.3, 0.3, 0.3, 1)
        )
        
        content.add_widget(note_text)
        content.add_widget(close_btn)
        
        popup = Popup(
            title=f'Note #{self.note["id"]}',
            content=content,
            size_hint=(0.9, 0.7)
        )
        close_btn.bind(on_release=popup.dismiss)
        popup.open()


class MainApp(App):
    """Main application class"""
    
    def build(self):
        """Build the application UI"""
        self.title = 'Simple Notes App'
        self.data_manager = DataManager()
        
        # Update app open counter
        self.data_manager.data['stats']['app_opens'] += 1
        self.data_manager.save_data()
        
        # Request permissions on Android
        if ANDROID_MODE:
            self.request_android_permissions()
        
        # Create main layout
        return self.create_main_interface()
    
    def request_android_permissions(self):
        """Request necessary Android permissions"""
        try:
            permissions = [
                Permission.WRITE_EXTERNAL_STORAGE,
                Permission.READ_EXTERNAL_STORAGE
            ]
            request_permissions(permissions, self.permission_callback)
        except Exception as e:
            print(f"Permission request error: {e}")
    
    def permission_callback(self, permissions, results):
        """Handle permission request results"""
        if all(results):
            print("All permissions granted")
            if PLYER_AVAILABLE:
                try:
                    notification.notify(
                        title='Notes App',
                        message='App is ready to use!'
                    )
                except:
                    pass
        else:
            print("Some permissions denied")
    
    def create_main_interface(self):
        """Create the main user interface"""
        main_layout = BoxLayout(orientation='vertical', padding=10, spacing=10)
        
        # Header section
        header = BoxLayout(
            orientation='horizontal',
            size_hint_y=0.1,
            spacing=10
        )
        
        title_label = Label(
            text='My Notes App',
            font_size=24,
            bold=True,
            size_hint_x=0.7,
            color=(0.1, 0.5, 0.9, 1)
        )
        
        info_btn = Button(
            text='ℹ',
            size_hint_x=0.15,
            background_color=(0.3, 0.3, 0.3, 1)
        )
        info_btn.bind(on_release=self.show_info)
        
        settings_btn = Button(
            text='⚙',
            size_hint_x=0.15,
            background_color=(0.3, 0.3, 0.3, 1)
        )
        settings_btn.bind(on_release=self.show_settings)
        
        header.add_widget(title_label)
        header.add_widget(info_btn)
        header.add_widget(settings_btn)
        
        # Notes display area
        self.notes_container = BoxLayout(
            orientation='vertical',
            size_hint_y=0.7,
            spacing=2
        )
        
        scroll_view = ScrollView()
        scroll_view.add_widget(self.notes_container)
        
        # Input area
        input_area = BoxLayout(
            orientation='horizontal',
            size_hint_y=0.2,
            spacing=10
        )
        
        self.note_input = TextInput(
            hint_text='Enter your note here...',
            multiline=False,
            size_hint_x=0.7
        )
        
        add_btn = Button(
            text='Add Note',
            size_hint_x=0.3,
            background_color=(0.2, 0.8, 0.3, 1),
            color=(1, 1, 1, 1)
        )
        add_btn.bind(on_release=self.add_note)
        
        input_area.add_widget(self.note_input)
        input_area.add_widget(add_btn)
        
        # Assemble main layout
        main_layout.add_widget(header)
        main_layout.add_widget(scroll_view)
        main_layout.add_widget(input_area)
        
        # Load existing notes
        self.refresh_notes_display()
        
        # Schedule periodic autosave
        Clock.schedule_interval(self.autosave, 30)
        
        return main_layout
    
    def add_note(self, instance):
        """Add a new note"""
        note_text = self.note_input.text.strip()
        
        if not note_text:
            self.show_popup('Error', 'Please enter some text')
            return
        
        # Add note
        self.data_manager.add_note(note_text)
        
        # Clear input
        self.note_input.text = ''
        
        # Refresh display
        self.refresh_notes_display()
        
        # Provide feedback
        if PLYER_AVAILABLE and platform == 'android':
            try:
                vibrator.vibrate(0.1)
            except:
                pass
        
        self.show_popup('Success', 'Note added!')
    
    def delete_note(self, note_id):
        """Delete a note"""
        self.data_manager.delete_note(note_id)
        self.refresh_notes_display()
    
    def refresh_notes_display(self):
        """Update the notes display"""
        self.notes_container.clear_widgets()
        
        notes = self.data_manager.get_notes()
        
        if not notes:
            no_notes_label = Label(
                text='No notes yet!\nStart typing below.',
                halign='center',
                valign='center',
                color=(0.5, 0.5, 0.5, 1)
            )
            self.notes_container.add_widget(no_notes_label)
        else:
            for note in reversed(notes):
                note_widget = NoteWidget(
                    note=note,
                    on_delete_callback=self.delete_note
                )
                self.notes_container.add_widget(note_widget)
    
    def show_popup(self, title, message):
        """Show a simple popup message"""
        content = BoxLayout(orientation='vertical', padding=10)
        message_label = Label(text=message)
        close_btn = Button(
            text='OK',
            size_hint_y=0.3,
            background_color=(0.3, 0.3, 0.3, 1)
        )
        
        content.add_widget(message_label)
        content.add_widget(close_btn)
        
        popup = Popup(
            title=title,
            content=content,
            size_hint=(0.8, 0.4)
        )
        close_btn.bind(on_release=popup.dismiss)
        popup.open()
    
    def show_info(self, instance):
        """Show application information"""
        stats = self.data_manager.data['stats']
        info_text = f"""
📱 Simple Notes App v1.0

📊 Statistics:
• App Opens: {stats['app_opens']}
• Total Notes: {stats['total_notes']}
• Last Open: {stats['last_open'] or 'Never'}

👤 User: {self.data_manager.data['settings']['username']}

💾 Storage: {self.data_manager.data_file}

📱 Platform: {platform}
        """
        
        self.show_popup('App Information', info_text)
    
    def show_settings(self, instance):
        """Show settings interface"""
        content = BoxLayout(orientation='vertical', padding=10, spacing=10)
        
        username_label = Label(text='Username:', size_hint_y=0.1)
        username_input = TextInput(
            text=self.data_manager.data['settings']['username'],
            size_hint_y=0.2
        )
        
        save_btn = Button(
            text='Save Settings',
            size_hint_y=0.2,
            background_color=(0.2, 0.6, 0.2, 1)
        )
        
        def save_settings(instance):
            self.data_manager.update_setting('username', username_input.text)
            popup.dismiss()
            self.show_popup('Settings', 'Settings saved!')
        
        save_btn.bind(on_release=save_settings)
        
        content.add_widget(username_label)
        content.add_widget(username_input)
        content.add_widget(save_btn)
        
        popup = Popup(
            title='Settings',
            content=content,
            size_hint=(0.9, 0.5)
        )
        popup.open()
    
    def autosave(self, dt):
        """Periodic autosave function"""
        if self.data_manager.data['settings']['auto_save']:
            self.data_manager.save_data()
    
    def on_stop(self):
        """Called when app is closing"""
        self.data_manager.save_data()
        print("App closing - data saved")
    
    def on_pause(self):
        """Called when app is paused (Android)"""
        self.data_manager.save_data()
        return True
    
    def on_resume(self):
        """Called when app is resumed (Android)"""
        self.refresh_notes_display()


if __name__ == '__main__':
    # Run the app
    try:
        MainApp().run()
    except Exception as e:
        print(f"App crashed: {e}")
        # On Android, you might want to show this error
        if ANDROID_MODE:
            try:
                with open('/sdcard/app_error.log', 'w') as f:
                    f.write(f"Error: {e}\n")
            except:
                pass
